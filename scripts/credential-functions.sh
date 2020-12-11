#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

# $1 -> Namespace to install
_install_sealed_secrets() {
    # install latest Sealed Secrets
    local INSTALL_KUBESEAL='N'
    local SEALED_SECRET_RELEASE=$(curl --silent "https://api.github.com/repos/bitnami-labs/sealed-secrets/releases/latest" | jq -r .tag_name)
    local SS_CONTROLLER_EXISTS=$(oc get Deployment sealed-secrets-controller -n kube-system)

    if [[ -f /usr/local/bin/kubeseal &&  ! -z "${SS_CONTROLLER_EXISTS}" ]]
    then
        local OLD_VERSION=$(kubeseal --version)
        echo
        echo "Do you wish to reinstall/upgrade sealed-secrets, kubeseal and controller?"
        echo -n "${OLD_VERSION} to ${SEALED_SECRET_RELEASE}? [Y/n] "
        read -t 10 -n 1 INSTALL_KUBESEAL
        echo
    else
        echo "Sealed Secrets not found..."
        INSTALL_KUBESEAL='Y'
    fi

    if [[ ${INSTALL_KUBESEAL} == 'Y' ]]
    then
        echo
        sudo rm -f /usr/local/bin/kubeseal /tmp/kubseal
        wget https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRET_RELEASE}/kubeseal-linux-amd64 -O /tmp/kubeseal
        sudo install -m 755 /tmp/kubeseal /usr/local/bin/kubeseal
        sudo rm -f /tmp/kubseal

        echo "kubeseal version ${SEALED_SECRET_RELEASE} installed"

        oc apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRET_RELEASE}/controller.yaml

        echo "Create custom cluster role for the management of sealedsecrets by Jenkins service accounts"
        oc apply -f ${TEMPLATES_DIR}/sealed-secrets-management.yml -n ${1}

        echo "Sealed Secrets Controller Version ${SEALED_SECRET_RELEASE} installed!"
    fi
}

# $1 -> Git repository name
# $2 -> Deploy Key Title
# $3 -> Deploy Key File
# $4 -> true/false: true for read only
_push_github_public_ssh_deploy_key() {
    local GIT_REPO_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE})
    local EL_CICD_GITHUB_URL="https://${GIT_REPO_ACCESS_TOKEN}@${EL_CICD_GIT_API_DOMAIN}/repos/${EL_CICD_ORGANIZATION}/${1}/keys"

    # DELETE old key, if any
    local KEY_ID=$(curl -ksS -X GET ${EL_CICD_GITHUB_URL} | jq ".[] | select(.title  == \"${2}\") | .id")
    if [[ ! -z ${KEY_ID} ]]
    then
        echo "Deleting old GitHub key ${2} with ID: ${KEY_ID}"
        curl -ksS -X DELETE ${EL_CICD_GITHUB_URL}/${KEY_ID}
    else
        echo "Adding key  ${2} to GitHub for first time" 
    fi

    # Add deploy key
    local READ_ONLY=${4}
    if [[ -z ${READ_ONLY} ]]
    then
        READ_ONLY=true
    fi

    local SECRET_FILE="${SECRET_FILE_TEMP_DIR}/sshKeyFile.json"
    cat ${TEMPLATES_DIR}/githubSshCredentials-prefix.json | sed "s/%DEPLOY_KEY_NAME%/${2}/" > ${SECRET_FILE}
    cat ${3}.pub >> ${SECRET_FILE}
    cat ${TEMPLATES_DIR}/githubSshCredentials-postfix.json >> ${SECRET_FILE}
    sed -i -e "s/%READ_ONLY%/${READ_ONLY}/" ${SECRET_FILE}

    curl -ksS -X POST -H Accept:application/vnd.github.v3+json -d @${SECRET_FILE} ${EL_CICD_GITHUB_URL}

    rm -f ${SECRET_FILE}
}

# $1 -> Environment; e.g. DEV or QA
# $2 -< Namespace
_create_env_docker_registry_secret() {
    echo
    echo "Creating ${1} image pull secret"
    U_NAME=$(eval echo \${${1}_IMAGE_REPO_USERNAME})
    SEC_NAME=$(eval echo \${${1}_IMAGE_REPO_PULL_SECRET})
    TKN_FILE=$(eval echo \${${1}_PULL_TOKEN_FILE})
    DOMAIN=$(eval echo \${${1}_IMAGE_REPO_DOMAIN})

    DRY_RUN=client
    if [[ ${OCP_VERSION} == 3 ]]
    then
        DRY_RUN=true
    fi

    SECRET_FILE_IN=${SECRET_FILE_TEMP_DIR}/${SEC_NAME}
    oc create secret docker-registry ${SEC_NAME}  \
        --docker-username=${U_NAME} \
        --docker-password=$(cat ${TKN_FILE}) \
        --docker-server=${DOMAIN} \
        --dry-run=${DRY_RUN} \
        -n ${2} \
        -o yaml > ${SECRET_FILE_IN}

    oc apply -f ${SECRET_FILE_IN} --overwrite -n ${2}

    LABEL_NAME=$(echo ${1} | tr '[:upper:]' '[:lower:]')-env
    oc label secret ${SEC_NAME} --overwrite ${LABEL_NAME}=true -n ${2}
}

# $1 -> Jenkins Domain
# $2 -> Credentials ID
# $3 -> Token file
_push_access_token_to_jenkins() {
    local SECRET_TOKEN=$(cat ${3})

    # NOTE: using '|' (pipe) as a delimeter in sed TOKEN replacement, since '/' is a legitimate token character
    local SECRET_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml
    cat ${TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${2}/; s|%TOKEN%|${SECRET_TOKEN}|" > ${SECRET_FILE}

    __push_creds_file_to_jenkins ${1} ${SECRET_FILE} ${2}

    rm -f ${SECRET_FILE}
}

# $1 -> Jenkins Domain
# $2 -> Credentials ID
# $3 -> shh private key file
_push_ssh_creds_to_jenkins() {
    local SECRET_FILE=${SECRET_FILE_TEMP_DIR}/secret.xml

    cat ${TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${2}/g" > ${SECRET_FILE}
    cat ${3} >> ${SECRET_FILE}
    cat ${TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE}

    __push_creds_file_to_jenkins ${1} ${SECRET_FILE} ${2}

    rm -f ${SECRET_FILE}
}

# $1 -> Jenkins Domain
# $2 -> Secret file to push
# $2 -> Credentials ID
__push_creds_file_to_jenkins() {
    local JENKINS_CREDS_URL="https://${1}/credentials/store/system/domain/_"

    local OC_BEARER_TOKEN_HEADER="Authorization: Bearer $(oc whoami -t)"
    local CONTENT_TYPE_XML="content-type:application/xml"

    # Create and update to make sure it takes
    curl -k -X POST -H "${OC_BEARER_TOKEN_HEADER}" -H "${CONTENT_TYPE_XML}" --data-binary @${2} "${JENKINS_CREDS_URL}/createCredentials"
    curl -k -X POST -H "${OC_BEARER_TOKEN_HEADER}" -H "${CONTENT_TYPE_XML}" --data-binary @${2} "${JENKINS_CREDS_URL}/credential/${3}/config.xml"
}