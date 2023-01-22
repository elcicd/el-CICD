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
    local PULL_SECRET_TYPES="jenkins ${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS/:/ } ${PRE_PROD_ENV} ${PROD}"
    for PULL_SECRET_TYPE in ${PULL_SECRET_TYPES}
    do
        if [[ ${PULL_SECRET_TYPE} == 'jenkins' ]]
        then
            local USERNAME_PWD_FILE="${SECRET_FILE_DIR}/${PULL_SECRET_TYPE@L}-${ONBOARDING_SERVER_TYPE}-${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
        else
            local USERNAME_PWD_FILE="${SECRET_FILE_DIR}/${PULL_SECRET_TYPE@L}${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
        fi
    
        if [[ ! -f ${USERNAME_PWD_FILE} ]]
        then
            local PULL_SECRET_FILES=${PULL_SECRET_FILES:+$PULL_SECRET_FILES, }${TKN_FILE}
        fi
    done

    if [[ ! -z ${PULL_SECRET_FILES} ]]
    then
        echo
        echo "ERROR:"
        echo "  MISSING THE FOLLOWING PULL SECRET FILES:"
        echo "    '${PULL_SECRET_FILES//${SECRET_FILE_DIR}\//}'"
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

_check_upgrade_sealed_secrets() {
    _check_sealed_secrets

    if [[ ${INSTALL_KUBESEAL} == ${_YES} ]]
    then
        _install_sealed_secrets
    fi
}

_check_sealed_secrets() {
    echo
    local CURRENT_SS_VERSION=$(helm list -f 'sealed-secrets' -o json -n kube-system | jq -r '.[0].app_version' )
    if [[ ${CURRENT_SS_VERSION} != 'null' ]]
    then
        echo "CURRENT INSTALLED SEALED SECRETS VERSION: ${CURRENT_SS_VERSION}"
    else
        echo 'NO CURRENTLY INSTALLED SEALED SECRETS FOUND'
    fi
    
    local LATEST_SS_VERSION=$(helm show chart sealed-secrets --repo https://bitnami-labs.github.io/sealed-secrets | grep appVersion | tr -d 'appVersion: ')
    echo "LATEST RELEASED SEALED SECRETS VERSION INFO: ${LATEST_SS_VERSION}"

    echo
    local MSG="Do you wish to reinstall/upgrade sealed-secrets and kubeseal to the latest release version? [Y/n] "
    INSTALL_KUBESEAL=$(_get_yes_no_answer "${MSG}")
}

_install_sealed_secrets() {
    set -e
    
    echo
    echo '================= SEALED SECRETS ================='
    echo
    echo 'Installing latest Sealed Secrets Helm chart'
    echo
    helm upgrade --install --atomic sealed-secrets --history-max 2 -n kube-system \
                 --set-string fullnameOverride=sealed-secrets-controller \
                 --repo https://bitnami-labs.github.io/sealed-secrets \
                 sealed-secrets
    echo
    echo '================= SEALED SECRETS ================='

    echo
    echo 'Downloading and copying kubeseal to /usr/local/bin for generating Sealed Secrets.'
    local SEALED_SECRETS_DIR=/tmp/sealedsecrets
    mkdir -p ${SEALED_SECRETS_DIR}
    local SS_VERSION=$(helm list -o json -n kube-system | jq -r '.[] | select (.name == "sealed-secrets") | .app_version' | tr -d v)
    local KUBESEAL_URL="https://github.com/bitnami-labs/sealed-secrets/releases/download/v${SS_VERSION}/kubeseal-${SS_VERSION}-linux-amd64.tar.gz"
    sudo rm -f ${SEALED_SECRETS_DIR}/kubeseal* /usr/local/bin/kubeseal
    wget -qc --show-progress ${KUBESEAL_URL} -O ${SEALED_SECRETS_DIR}/kubeseal.tar.gz
    tar -xvzf ${SEALED_SECRETS_DIR}/kubeseal.tar.gz -C ${SEALED_SECRETS_DIR}
    sudo install -m 755 ${SEALED_SECRETS_DIR}/kubeseal /usr/local/bin/kubeseal
    
    set +e
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
    local PULL_SECRET_TYPES="jenkins ${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS/:/ } ${PRE_PROD_ENV}"
    echo
    echo "Creating the image repository pull secrets for each environment and Jenkins: ${PULL_SECRET_TYPES}"
    
    for PULL_SECRET_TYPE in ${PULL_SECRET_TYPES}
    do
        local APP_NAME="el-cicd-${PULL_SECRET_TYPE@L}-pull-secret"
        local APP_NAMES="${APP_NAMES:+$APP_NAMES,}${APP_NAME}"

        if [[ ${PULL_SECRET_TYPE} == 'jenkins' ]]
        then
            local USERNAME_PWD_FILE="${SECRET_FILE_DIR}/${PULL_SECRET_TYPE@L}-${ONBOARDING_SERVER_TYPE}${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
        else
            local USERNAME_PWD_FILE="${SECRET_FILE_DIR}/${PULL_SECRET_TYPE@L}${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
        fi
        
        local SET_FLAGS="${SET_FLAGS:+$SET_FLAGS }--set-file elCicdDefs-${APP_NAME}.USERNAME_PWD=${USERNAME_PWD_FILE}"
        local SERVER=$(eval echo \${${PULL_SECRET_TYPE}${IMAGE_REGISTRY_POSTFIX}})
        SET_FLAGS="${SET_FLAGS} --set-string elCicdDefs-${APP_NAME}.SERVER=${SERVER:-${JENKINS_IMAGE_REGISTRY}}"
        SET_FLAGS="${SET_FLAGS} --set-string elCicdDefs-${APP_NAME}.PULL_SECRET_TYPE=${PULL_SECRET_TYPE@L}"
    done

    set -ex
    helm upgrade --create-namespace --atomic --install --history-max=1 \
        --set-string elCicdDefs.IMAGE_SECRET_APP_NAMES="{${APP_NAMES}}" \
        ${SET_FLAGS} \
        -n ${ONBOARDING_MASTER_NAMESPACE} \
        -f ${EL_CICD_HELM_DIR}/cicd-image-registry-secrets-values.yaml \
        el-cicd-pull-secrets \
        elCicdCharts/elCicdChart
    set +ex
}

_push_access_token_to_jenkins() {
    local JENKINS_DOMAIN=${1}
    local CREDS_ID=${2}
    local TKN_FILE=${3}

    local SECRET_TOKEN=$(cat ${TKN_FILE})

    # NOTE: using '|' (pipe) as a delimeter in sed TOKEN replacement, since '/' is a legitimate token character
    local SECRET_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml
    cat ${EL_CICD_TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${CREDS_ID}/; s|%TOKEN%|${SECRET_TOKEN}|" > ${SECRET_FILE}

    __push_creds_file_to_jenkins ${JENKINS_DOMAIN} ${CREDS_ID} ${SECRET_FILE}

    rm -f ${SECRET_FILE}
}

_push_username_pwd_to_jenkins() {
    local JENKINS_DOMAIN=${1}
    local CREDS_ID=${2}
    local TKN_FILE=${3}

    IFS=':' read -ra USERNAME_PWD <<< $(cat ${TKN_FILE} | tr -d '[:space:]')

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

_podman_login() {
    echo
    echo -n 'Podman: '
    JENKINS_USERNAME_PWD_FILE="jenkins-${ONBOARDING_SERVER_TYPE}${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
    IFS=':' read -r -a JENKINS_USERNAME_PWD <<< $(cat ${SECRET_FILE_DIR}/${JENKINS_USERNAME_PWD_FILE})
    if [[ ! -z ${JENKINS_USERNAME_PWD} ]]
    then
        podman login --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} -u ${JENKINS_USERNAME_PWD[0]} -p ${JENKINS_USERNAME_PWD[1]} ${JENKINS_IMAGE_REGISTRY}
    else
        echo "ERROR: UNABLE TO FIND JENKINS IMAGE REGISTRY USERNAME/PASSWORD FILE: ${SECRET_FILE_DIR}/${JENKINS_USERNAME_PWD_FILE}"
        exit 1
    fi
}