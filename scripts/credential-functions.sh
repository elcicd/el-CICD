#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export GITHUB_REST_API_HDR='Accept: application/vnd.github.v3+json'

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
        oc apply -f ${TEMPLATES_DIR}/sealed-secrets-management.yml -n ${ONBOARDING_MASTER_NAMESPACE}

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

    local GITHUB_BEARER_TOKEN="Authorization: Bearer $(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE})"
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
    cp ${TEMPLATES_DIR}/${TEMPLATE_FILE} ${GITHUB_CREDS_FILE}
    sed -i -e "s/%DEPLOY_KEY_TITLE%/${DEPLOY_KEY_TITLE}/g" ${GITHUB_CREDS_FILE}
    GITHUB_CREDS=$(<${GITHUB_CREDS_FILE})
    echo "${GITHUB_CREDS//%DEPLOY_KEY%/$(<${DEPLOY_KEY_FILE})}" > ${GITHUB_CREDS_FILE}
    
    local RESULT=$(curl -fksS -X POST -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" -d @${GITHUB_CREDS_FILE} ${EL_CICD_GITHUB_KEYS_URL} | \
        jq 'del(.key)')
    printf "New GitHub key created:\n${RESULT}\n"

    rm -f ${GITHUB_CREDS_FILE}
}

_create_env_image_registry_secret() {
    local ENV=${1}
    local NAMESPACE_NAME=${2}

    local USER_NAME=$(eval echo \${${ENV}${IMAGE_REPO_USERNAME_POSTFIX}})
    local SECRET_NAME=$(eval echo \${${ENV}${IMAGE_REPO_PULL_SECRET_POSTFIX}})
    local TKN_FILE=$(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})
    local DOMAIN=$(eval echo \${${ENV}${IMAGE_REPO_POSTFIX}})

    echo
    echo "Creating secret ${SECRET_NAME} for SDLC environment ${ENV}"
    local SECRET_FILE_IN=${SECRET_FILE_TEMP_DIR}/${SECRET_NAME}
    oc create secret docker-registry ${SECRET_NAME}  \
        --docker-username=${USER_NAME} \
        --docker-password=$(cat ${TKN_FILE}) \
        --docker-server=${DOMAIN} \
        --dry-run=client \
        -o yaml > ${SECRET_FILE_IN}

    oc apply -f ${SECRET_FILE_IN} --overwrite -n ${NAMESPACE_NAME}

    local LABEL_NAME=$(echo ${ENV} | tr '[:upper:]' '[:lower:]')-env
    echo "Applying label ${LABEL_NAME} to secret ${SECRET_NAME}"
    oc label secret ${SECRET_NAME} --overwrite ${LABEL_NAME}=true -n ${NAMESPACE_NAME}
}

_push_access_token_to_jenkins() {
    local JENKINS_DOMAIN=${1}
    local CREDS_ID=${2}
    local TKN_FILE=${3}

    local SECRET_TOKEN=$(cat ${TKN_FILE})

    # NOTE: using '|' (pipe) as a delimeter in sed TOKEN replacement, since '/' is a legitimate token character
    local JENKINS_CREDS_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml
    cat ${TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${CREDS_ID}/; s|%TOKEN%|${SECRET_TOKEN}|" > ${JENKINS_CREDS_FILE}

    __push_creds_file_to_jenkins ${JENKINS_DOMAIN} ${CREDS_ID} ${JENKINS_CREDS_FILE}

    rm -f ${JENKINS_CREDS_FILE}
}

_push_ssh_creds_to_jenkins() {
    local JENKINS_URL=${1}
    local CREDS_ID=${2}
    local DEPLOY_KEY_FILE=${3}
    
    local TEMPLATE_FILE='jenkinsSshCredentials-template.xml'
    local JENKINS_CREDS_FILE="${SECRET_FILE_TEMP_DIR}/${TEMPLATE_FILE}"
    cp ${TEMPLATES_DIR}/${TEMPLATE_FILE} ${JENKINS_CREDS_FILE}
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
    echo "Looking for custom credentials script '${CUSTOM_CREDENTIALS_SCRIPT}' in ${CONFIG_REPOSITORY_BOOTSTRAP}..."
    if [[ -f ${CONFIG_REPOSITORY_BOOTSTRAP}/${CUSTOM_CREDENTIALS_SCRIPT} ]]
    then
        echo "Found ${CUSTOM_CREDENTIALS_SCRIPT}; running..."
        ${CONFIG_REPOSITORY_BOOTSTRAP}/${CUSTOM_CREDENTIALS_SCRIPT}
        echo "Custom script ${CUSTOM_CREDENTIALS_SCRIPT} completed"
    else
        echo "Custom script '${CUSTOM_CREDENTIALS_SCRIPT}' not found."
    fi
}