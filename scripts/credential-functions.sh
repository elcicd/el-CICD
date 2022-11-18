#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export GITHUB_REST_API_HDR='Accept: application/vnd.github.v3+json'

_verify_scm_secret_files_exist() {
    if [[ ! -f ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} ||
          ! -f ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub ||
          ! -f ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} ||
          ! -f ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub ||
          ! -f ${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE} ]]
    then
        echo
        echo "ERROR:"
        echo "  MISSING ONE OR MORE OF THE FOLLOWING CREDENTIALS FILES:"
        echo "    ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}.pub"
        echo "    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}.pub"
        echo "    ${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE//${SECRET_FILE_DIR}\//}"
        __verify_continue
    fi
}

_verify_pull_secret_files_exist() {    
    CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS/:/ } ${PRE_PROD_ENV} ${PROD}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        local TKN_FILE=${SECRET_FILE_DIR}/${ENV@L}${IMAGE_REGISTRY_PULL_TOKEN_ID_POSTFIX}
        if [[ ! -f ${TKN_FILE} ]]
        then
            local TOKEN_FILES=${TOKEN_FILES:+$TOKEN_FILES, }${TKN_FILE}
        fi
    done
    
    if [[ ! -z ${TOKEN_FILES} ]]
    then
        echo
        echo "ERROR:"
        echo "  MISSING THE FOLLOWING ENV PULL SECRET TOKEN FILES:"
        echo "    '${TOKEN_FILES//${SECRET_FILE_DIR}\//}'"
        __verify_continue
    fi
}

__verify_continue() {
    echo
    echo "IT IS STRONGLY ADVISED YOU EXIT, FIX THE PROBLEM, AND RE-RUN THE UTILITY"
    echo
    CONTINUE=$(_get_yes_no_answer 'ARE YOU SURE YOU WISH TO CONTINUTE? [Y/n] ')
    if [[ ${CONTINUE} != ${_YES} ]]
    then
        exit 1
    fi
}

_check_sealed_secrets() {
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
        # install latest Sealed Secrets
        local SS_CONTROLLER_EXISTS=$(oc get Deployment sealed-secrets-controller --ignore-not-found -n kube-system)
        if [[ -f /usr/local/bin/kubeseal && ! -z "${SS_CONTROLLER_EXISTS}" ]]
        then
            local OLD_VERSION=$(kubeseal --version)
            echo
            local MSG="Do you wish to reinstall/upgrade sealed-secrets, kubeseal and controller from  ${OLD_VERSION} to ${SEALED_SECRET_RELEASE_VERSION}? [Y/n] "
        else
            local MSG="Do you wish to install sealed-secrets, kubeseal and controller, version ${SEALED_SECRET_RELEASE_VERSION}? [Y/n] "
        fi
        INSTALL_KUBESEAL=$(_get_yes_no_answer "${MSG}")
    fi
}

_install_sealed_secrets() {
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
        local SEALED_SECRETS_DIR=/tmp/sealedsecrets
        local SEALED_SECRETS_URL=https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRET_RELEASE_VERSION}
        mkdir -p ${SEALED_SECRETS_DIR}
        echo
        echo 'Downloading and copying kubeseal to /usr/local/bin for generating Sealed Secrets.'
        sudo rm -f /usr/local/bin/kubeseal /tmp/kubseal
        wget -q --show-progress ${SEALED_SECRETS_URL}/kubeseal-${SEALED_SECRET_RELEASE_VERSION#v}-linux-amd64.tar.gz -O ${SEALED_SECRETS_DIR}/kubeseal.tar.gz
        tar xvfz ${SEALED_SECRETS_DIR}/kubeseal.tar.gz -C ${SEALED_SECRETS_DIR}
        sudo install -m 755 ${SEALED_SECRETS_DIR}/kubeseal /usr/local/bin/kubeseal

        echo "kubeseal version ${SEALED_SECRET_RELEASE_VERSION} installed"

        echo
        echo 'Deploying Sealed Secrets controller to cluster.'
        wget -q --show-progress ${SEALED_SECRETS_URL}/controller.yaml -O ${SEALED_SECRETS_DIR}/controller.yaml

        #HACK: REMOVE v1beta1 FOR K8S >=1.22 (v1beta1 apiVersion NOT supported; will remove when assured everyone is on later version)
        sed -i -e 's/v1beta1/v1/g' ${SEALED_SECRETS_DIR}/controller.yaml #TODO: REMOVE HACK FOR K8S >=1.22 (no more v1beta1 apiVersion supported)
        oc apply -f ${SEALED_SECRETS_DIR}/controller.yaml

        echo
        echo "Create custom cluster role for the management of sealedsecrets resources by Jenkins service accounts"
        echo "NOTE: Without custom cluster role, only cluster admins could manage sealedsecrets."
        oc apply -f ${EL_CICD_TEMPLATES_DIR}/sealed-secrets-management.yml -n ${ONBOARDING_MASTER_NAMESPACE}

        echo
        echo "Sealed Secrets Controller Version ${SEALED_SECRET_RELEASE_VERSION} installed."

        rm -rf ${SEALED_SECRETS_DIR}
    else
        echo 'ERROR: SEALED_SECRET_RELEASE_VERSION undefined.'
        exit 1
    fi
}

_push_deploy_key_to_github() {
    local GIT_REPO_NAME=${1}
    local DEPLOY_KEY_TITLE=${2}
    local DEPLOY_KEY_FILE="${3}.pub"

    # READ_ONLY *MUST* be ${_FALSE} to push a read/write key
    local READ_ONLY=${4}
    if [[ ${READ_ONLY} != ${_FALSE} ]]
    then
        READ_ONLY=true
    fi

    local GITHUB_BEARER_TOKEN="Authorization: Bearer $(cat ${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE})"
    local EL_CICD_GITHUB_KEYS_URL="https://${EL_CICD_GIT_API_URL}/repos/${EL_CICD_ORGANIZATION}/${GIT_REPO_NAME}/keys"

    # DELETE old key, if any
    local KEY_ID=$(curl -ksS -X GET -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" ${EL_CICD_GITHUB_KEYS_URL} | \
        jq ".[] | select(.title  == \"${DEPLOY_KEY_TITLE}\") | .id" 2>/dev/null)
    if [[ ! -z ${KEY_ID} ]]
    then
        echo "Deleting old GitHub key ${DEPLOY_KEY_TITLE} with ID: ${KEY_ID}"
        curl -fksS -X DELETE -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" ${EL_CICD_GITHUB_KEYS_URL}/${KEY_ID} | \
            jq 'del(.key)'
    else
        echo "Adding key  ${DEPLOY_KEY_TITLE} to GitHub for first time" 
    fi

    local TEMPLATE_FILE='githubDeployKey-template.json'
    local GITHUB_CREDS_FILE="${SECRET_FILE_TEMP_DIR}/${TEMPLATE_FILE}"
    cp ${EL_CICD_TEMPLATES_DIR}/${TEMPLATE_FILE} ${GITHUB_CREDS_FILE}
    sed -i -e "s/%DEPLOY_KEY_TITLE%/${DEPLOY_KEY_TITLE}/g" ${GITHUB_CREDS_FILE}
    GITHUB_CREDS=$(<${GITHUB_CREDS_FILE})
    echo "${GITHUB_CREDS//%DEPLOY_KEY%/$(<${DEPLOY_KEY_FILE})}" > ${GITHUB_CREDS_FILE}
    
    local RESULT=$(curl -fksS -X POST -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" -d @${GITHUB_CREDS_FILE} ${EL_CICD_GITHUB_KEYS_URL} | \
        jq 'del(.key)')
    printf "New GitHub key created:\n${RESULT}\n"

    rm -f ${GITHUB_CREDS_FILE}
}

_create_env_image_registry_secrets() {
    local CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS/:/ } ${PRE_PROD_ENV}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        local APP_NAME="${ENV@L}-pull-secret"
        local APP_NAMES="${APP_NAMES:+$APP_NAMES,}${APP_NAME}"
        local SET_FILE="${SET_FILE:+$SET_FILE }--set-file elCicdDefs.el-cicd-${APP_NAME}=${SECRET_FILE_DIR}/${ENV@L}${IMAGE_REGISTRY_PULL_TOKEN_ID_POSTFIX}"
        local SERVER=$(eval echo \${${ENV}${IMAGE_REGISTRY_POSTFIX}})
        local SET_STRING="${SET_STRING:+$SET_STRING }--set-string elCicdDefs-${APP_NAME}.ENV=${ENV@L}"
        SET_STRING="${SET_STRING} --set-string elCicdDefs.${APP_NAME}${IMAGE_REGISTRY_POSTFIX}=${SERVER}"
    done
    
    set -x
    helm upgrade --create-namespace --atomic --install --history-max=1 \
        --set-string elCicdDefs.IMAGE_SECRET_APP_NAMES="{${APP_NAMES}}" \
        ${SET_FILE} \
        ${SET_STRING} \
        -n ${ONBOARDING_MASTER_NAMESPACE} \
        -f ${EL_CICD_HELM_DIR}/sdlc-image-registry-secrets-values.yaml \
        el-cicd-pull-secrets \
        elCicdCharts/elCicdChart
    set +x
}

_push_access_token_to_jenkins() {
    local JENKINS_DOMAIN=${1}
    local CREDS_ID=${2}
    local TKN_FILE=${3}

    IFS=':' read -ra USERNAME_PWD <<< $(cat ${TKN_FILE})

    local JENKINS_CREDS_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml
    local SED_EXPR="s/%ID%/${CREDS_ID}/; s|%USERNAME%|${USERNAME_PWD[0]}|; s|%PASSWORD%|${USERNAME_PWD[1]}|"
    cat ${EL_CICD_TEMPLATES_DIR}/jenkinsUsernamePasswordCreds-template.xml | sed "${SED_EXPR}" > ${JENKINS_CREDS_FILE}

    __push_creds_file_to_jenkins ${JENKINS_DOMAIN} ${CREDS_ID} ${JENKINS_CREDS_FILE}

    rm -f ${JENKINS_CREDS_FILE}
}

_push_ssh_creds_to_jenkins() {
    local CREDS_ID=${2}
    local DEPLOY_KEY_FILE=${3}
    
    local TEMPLATE_FILE='jenkinsSshCredentials-template.xml'
    local JENKINS_CREDS_FILE="${SECRET_FILE_TEMP_DIR}/${TEMPLATE_FILE}"
    cp ${EL_CICD_TEMPLATES_DIR}/${TEMPLATE_FILE} ${JENKINS_CREDS_FILE}
    sed -i -e "s/%UNIQUE_ID%/${CREDS_ID}/g" ${JENKINS_CREDS_FILE}
    JENKINS_CREDS=$(<${JENKINS_CREDS_FILE})
    echo "${JENKINS_CREDS//%PRIVATE_KEY%/$(<${DEPLOY_KEY_FILE})}" > ${JENKINS_CREDS_FILE}

    __push_creds_file_to_jenkins ${JENKINS_URL} ${CREDS_ID} ${JENKINS_CREDS_FILE} 

    rm -f ${JENKINS_CREDS_FILE}
}

__push_creds_file_to_jenkins() {
    local JENKINS_DOMAIN=${1}
    local CREDS_ID=${2}
    local JENKINS_CREDS_FILE=${3}

    local JENKINS_CREDS_URL="https://${JENKINS_DOMAIN}/credentials/store/system/domain/_"

    local OC_BEARER_TOKEN_HEADER="Authorization: Bearer $(oc whoami -t)"
    local CONTENT_TYPE_XML="content-type:application/xml"

    # Create and update to make sure it takes
    curl -ksS -o /dev/null -X POST -H "${OC_BEARER_TOKEN_HEADER}" -H "${CONTENT_TYPE_XML}" --data-binary @${JENKINS_CREDS_FILE} \
        "${JENKINS_CREDS_URL}/createCredentials"
    curl -fksS -X POST -H "${OC_BEARER_TOKEN_HEADER}" -H "${CONTENT_TYPE_XML}" --data-binary @${JENKINS_CREDS_FILE} \
        "${JENKINS_CREDS_URL}/credential/${CREDS_ID}/config.xml"
}

_run_custom_credentials_script() {
    # $1 -> should be a value of 'prod' or 'non-prod'
    local CUSTOM_CREDENTIALS_SCRIPT=secrets-${1}.sh

    echo
    echo "Looking for custom credentials script '${CUSTOM_CREDENTIALS_SCRIPT}' in ${EL_CICD_CONFIG_BOOTSTRAP_DIR}..."
    if [[ -f ${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${CUSTOM_CREDENTIALS_SCRIPT} ]]
    then
        echo "Found ${CUSTOM_CREDENTIALS_SCRIPT}; running..."
        ${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${CUSTOM_CREDENTIALS_SCRIPT}
        echo "Custom script ${CUSTOM_CREDENTIALS_SCRIPT} completed"
    else
        echo "Custom script '${CUSTOM_CREDENTIALS_SCRIPT}' not found."
    fi
}