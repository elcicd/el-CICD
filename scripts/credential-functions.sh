#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

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

_push_github_public_ssh_deploy_key() {
    local GIT_REPO_NAME=${1}
    local DEPLOY_KEY_TITLE=${2}
    local DEPLOY_KEY_FILE=${3}

    # READ_ONLY *MUST* be ${_FALSE} to push a read/write key
    local READ_ONLY=${4}
    if [[ ${READ_ONLY} != ${_FALSE} ]]
    then
        READ_ONLY=true
    fi

    local GIT_REPO_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE})
    local EL_CICD_GITHUB_URL="https://${GIT_REPO_ACCESS_TOKEN}@${EL_CICD_GIT_API_URL}/repos/${EL_CICD_ORGANIZATION}/${1}/keys"

    # DELETE old key, if any
    local KEY_ID=$(curl -ksS -X GET ${EL_CICD_GITHUB_URL} | jq ".[] | select(.title  == \"${2}\") | .id")
    if [[ ! -z ${KEY_ID} ]]
    then
        echo "Deleting old GitHub key ${2} with ID: ${KEY_ID}"
        curl -ksS -X DELETE ${EL_CICD_GITHUB_URL}/${KEY_ID}
    else
        echo "Adding key  ${2} to GitHub for first time" 
    fi

    local SECRET_FILE="${SECRET_FILE_TEMP_DIR}/sshKeyFile.json"
    cat ${TEMPLATES_DIR}/githubSshCredentials-prefix.json | sed "s/%DEPLOY_KEY_NAME%/${2}/" > ${SECRET_FILE}
    cat ${3}.pub >> ${SECRET_FILE}
    cat ${TEMPLATES_DIR}/githubSshCredentials-postfix.json >> ${SECRET_FILE}
    sed -i -e "s/%READ_ONLY%/${READ_ONLY}/" ${SECRET_FILE}

    curl -ksS -X POST -H Accept:application/vnd.github.v3+json -d @${SECRET_FILE} ${EL_CICD_GITHUB_URL}

    rm -f ${SECRET_FILE}
}

_create_env_image_registry_secret() {
    local ENV=${1}
    local NAMESPACE_NAME=${2}

    local USER_NAME=$(eval echo \${${ENV}${IMAGE_REPO_USERNAME_POSTFIX}})
    local SECRET_NAME=$(eval echo \${${ENV}${IMAGE_REPO_PULL_SECRET_POSTFIX}})
    local TKN_FILE=$(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})
    local DOMAIN=$(eval echo \${${ENV}${IMAGE_REPO_POSTFIX}})

    local DRY_RUN=client
    if [[ ${OCP_VERSION} == 3 ]]
    then
        # option values changed in future versions of kubectl/oc cli
        DRY_RUN=true
    fi

    echo
    echo "Creating secret ${SECRET_NAME} for SDLC environment ${ENV}"
    local SECRET_FILE_IN=${SECRET_FILE_TEMP_DIR}/${SECRET_NAME}
    oc create secret docker-registry ${SECRET_NAME}  \
        --docker-username=${USER_NAME} \
        --docker-password=$(cat ${TKN_FILE}) \
        --docker-server=${DOMAIN} \
        --dry-run=${DRY_RUN} \
        -n ${NAMESPACE_NAME} \
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
    local SECRET_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml
    cat ${TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${CREDS_ID}/; s|%TOKEN%|${SECRET_TOKEN}|" > ${SECRET_FILE}

    __push_creds_file_to_jenkins ${JENKINS_DOMAIN} ${SECRET_FILE} ${CREDS_ID}

    rm -f ${SECRET_FILE}
}

_push_ssh_creds_to_jenkins() {
    local JENKINS_URL=${1}
    local CREDS_ID=${2}

    local SECRET_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml

    cat ${TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${CREDS_ID}/g" > ${SECRET_FILE}
    cat ${3} >> ${SECRET_FILE}
    cat ${TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE}

    __push_creds_file_to_jenkins ${JENKINS_URL} ${SECRET_FILE} ${CREDS_ID}

    rm -f ${SECRET_FILE}
}

__push_creds_file_to_jenkins() {
    local JENKINS_DOMAIN=${1}
    local SECRET_FILE=${2}
    local CREDS_ID=${3}

    local JENKINS_CREDS_URL="https://${JENKINS_DOMAIN}/credentials/store/system/domain/_"

    local OC_BEARER_TOKEN_HEADER="Authorization: Bearer $(oc whoami -t)"
    local CONTENT_TYPE_XML="content-type:application/xml"

    # Create and update to make sure it takes
    curl -ksS -X POST -H "${OC_BEARER_TOKEN_HEADER}" -H "${CONTENT_TYPE_XML}" --data-binary @${SECRET_FILE} "${JENKINS_CREDS_URL}/createCredentials"
    curl -ksS -X POST -H "${OC_BEARER_TOKEN_HEADER}" -H "${CONTENT_TYPE_XML}" --data-binary @${SECRET_FILE} "${JENKINS_CREDS_URL}/credential/${CREDS_ID}/config.xml"
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