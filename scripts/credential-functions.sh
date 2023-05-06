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
        local USERNAME_PWD_FILE="${SECRET_FILE_DIR}/${IMAGE_REGISTRY_PULL_SECRET_PREFIX}${PULL_SECRET_TYPE@L}-${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"

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

_check_upgrade_install_sealed_secrets() {
    _collect_sealed_secret_info

    _confirm_upgrade_install_sealed_secrets

    if [[ ${INSTALL_SEALED_SECRETS} == ${_YES} ]]
    then
        _install_sealed_secrets
    else
        exit 0
    fi
}

_collect_sealed_secret_info() {
    echo
    HAS_SEALED_SECRETS=$(helm list --short --filter 'sealed-secrets' -n kube-system)
    if [[ ! -z ${HAS_SEALED_SECRETS} ]]
    then
        echo "CURRENTLY INSTALLED SEALED SECRETS VERSION INFO:"
        helm list --filter 'sealed-secrets' --time-format "2006-01-02" -n kube-system
    else
        echo 'NO CURRENTLY INSTALLED SEALED SECRETS VERSION FOUND: Use the --sealed-secrets flag to install.'
    fi

    local SS_URL='https://bitnami-labs.github.io/sealed-secrets'
    if [[ -z ${SEALED_SECRETS_CHART_VERSION} ]]
    then
        SEALED_SECRETS_CHART_VERSION=$(helm show chart sealed-secrets --repo ${SS_URL} | grep version | tr -d 'version: ')
    fi

    SEALED_SECRETS_RELEASE_VERSION=$(helm show chart sealed-secrets --version ${SEALED_SECRETS_CHART_VERSION} --repo ${SS_URL} | grep appVersion)
    SEALED_SECRETS_RELEASE_VERSION=$(echo ${SEALED_SECRETS_RELEASE_VERSION} | tr -d 'appVersion: ')
    SEALED_SECRETS_RELEASE_INFO="Helm Chart ${SEALED_SECRETS_CHART_VERSION} / Release ${SEALED_SECRETS_RELEASE_VERSION}"
}

_confirm_upgrade_install_sealed_secrets() {
    echo
    echo "SEALED SECRETS VERSION TO BE INSTALLED: ${SEALED_SECRETS_RELEASE_INFO}"

    echo
    local MSG="Do you wish to install/upgrade sealed-secrets and kubeseal to ${SEALED_SECRETS_RELEASE_INFO}? [Y/n] "
    INSTALL_SEALED_SECRETS=$(_get_yes_no_answer "${MSG}")
}

_install_sealed_secrets() {
    set -e

    echo
    echo '================= SEALED SECRETS ================='
    echo
    echo "Installing Sealed Secrets ${_BOLD}${SEALED_SECRETS_RELEASE_INFO}${_REGULAR}"
    echo
    helm upgrade --install --atomic sealed-secrets --history-max 2 -n kube-system \
                 --set-string fullnameOverride=sealed-secrets-controller \
                 --repo https://bitnami-labs.github.io/sealed-secrets \
                 --version ${SEALED_SECRETS_CHART_VERSION} \
                 sealed-secrets
    echo
    echo '================= SEALED SECRETS ================='

    echo
    echo 'Downloading and copying kubeseal to /usr/local/bin for generating Sealed Secrets.'
    local SEALED_SECRETS_DIR=/tmp/sealedsecrets
    mkdir -p ${SEALED_SECRETS_DIR}
    local KUBESEAL_URL="https://github.com/bitnami-labs/sealed-secrets/releases/download"
    KUBESEAL_URL="${KUBESEAL_URL}/${SEALED_SECRETS_RELEASE_VERSION}/kubeseal-${SEALED_SECRETS_RELEASE_VERSION:1}-linux-amd64.tar.gz"
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
    local KEY_ID=$(curl -ksSL -X GET -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" ${EL_CICD_GITHUB_KEYS_URL} | \
        jq ".[] | select(.title  == \"${DEPLOY_KEY_TITLE}\") | .id" 2>/dev/null)
    if [[ ! -z ${KEY_ID} ]]
    then
        echo "Deleting old GitHub key ${DEPLOY_KEY_TITLE} with ID: ${KEY_ID}"
        curl -fksSL -X DELETE -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" ${EL_CICD_GITHUB_KEYS_URL}/${KEY_ID} | \
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

    local RESULT=$(curl -fksSL -X POST -H "${GITHUB_BEARER_TOKEN}" -H "${GITHUB_REST_API_HDR}" -d @${GITHUB_CREDS_FILE} ${EL_CICD_GITHUB_KEYS_URL} | \
        jq 'del(.key)')
    printf "New GitHub key created:\n${RESULT}\n"

    rm -f ${GITHUB_CREDS_FILE}
}

_create_jenkins_secrets() {
    local NONPROD_PULL_SECRET_TYPES="${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS//:/ }"
    local PULL_SECRET_TYPES="JENKINS ${EL_CICD_MASTER_NONPROD:+${NONPROD_PULL_SECRET_TYPES} }${PRE_PROD_ENV}"
    PULL_SECRET_TYPES="${PULL_SECRET_TYPES:+${PULL_SECRET_TYPES} }${EL_CICD_MASTER_PROD:+${PROD_ENV}}"

    local SET_FLAGS=$(__create_helm_image_registry_values_flags "${PULL_SECRET_TYPES}")

	if [[ ! -z ${EL_CICD_MASTER_NONPROD} && ! -z "$(ls -A ${BUILD_SECRETS_FILE_DIR})" ]]
    then
        local PROFILE_FLAG="--set-string elCicdProfiles={builder-secrets}"
        SET_FLAGS+="${SET_FLAGS:+ }$(__create_builder_secret_flags)"
    fi

    echo
    echo "Creating all Jenkins secrets..."
    echo
    local PULL_SECRET_NAMES=$(echo ${PULL_SECRET_TYPES@L} | sed -e 's/\s\+/,/g')
    local GIT_REPO_KEYS="${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID},${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}"
    set -e
    helm upgrade --atomic --install --history-max=1 \
        ${PROFILE_FLAG}  \
        --set-string elCicdDefs.BUILD_SECRETS_NAME=${EL_CICD_BUILD_SECRETS_NAME} \
        --set-string elCicdDefs.PULL_SECRET_NAMES="{${PULL_SECRET_NAMES}}" \
        --set-string elCicdDefs.SCM_REPO_SSH_KEY_IDS="{${GIT_REPO_KEYS}}" \
        --set-file elCicdDefs-${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.SCM_REPO_SSH_KEY=${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-file elCicdDefs-${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.SCM_REPO_SSH_KEY=${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-string elCicdDefs.SCM_ACCESS_TOKEN_ID=${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_ID} \
        --set-file elCicdDefs.SCM_ACCESS_TOKEN=${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE} \
        ${SET_FLAGS} \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/el-cicd-jenkins-secrets-values.yaml \
        el-cicd-jenkins-secrets \
        elCicdCharts/elCicdChart
    set +e
}

__create_helm_image_registry_values_flags() {
    local PULL_SECRET_TYPES=${1}

    for PULL_SECRET_TYPE in ${PULL_SECRET_TYPES}
    do
        local USERNAME_PWD_FILE="${SECRET_FILE_DIR}/$(__get_pull_secret_id ${PULL_SECRET_TYPE})"

        local USERNAME=$(jq -r .username ${USERNAME_PWD_FILE})
        local PASSWORD=$(jq -r .password ${USERNAME_PWD_FILE})

        local SET_FLAGS+="${SET_FLAGS:+ }--set-string elCicdDefs-${PULL_SECRET_TYPE@L}.REGISTRY_USERNAME=${USERNAME}"
        SET_FLAGS+="${SET_FLAGS:+ }--set-string elCicdDefs-${PULL_SECRET_TYPE@L}.REGISTRY_PASSWORD=${PASSWORD}"

        local IMAGE_REGISTRY_URL=$(eval echo \${${PULL_SECRET_TYPE}${IMAGE_REGISTRY_POSTFIX}})
        SET_FLAGS+="${SET_FLAGS:+ }--set-string elCicdDefs-${PULL_SECRET_TYPE@L}.IMAGE_REGISTRY_URL=${IMAGE_REGISTRY_URL}"
    done

    echo ${SET_FLAGS}
}

__create_builder_secret_flags() {
    for BUILDER_SECRET_FILE in ${BUILD_SECRETS_FILE_DIR}/*
    do
        local BUILDER_SECRET_KEY=$(basename ${BUILDER_SECRET_FILE})
        local SET_FLAGS="${SET_FLAGS:+${SET_FLAGS} }--set-file elCicdDefs.BUILDER_SECRET_FILES.${BUILDER_SECRET_KEY//[.]/\\.}=${BUILDER_SECRET_FILE}"
    done

    echo ${SET_FLAGS}
}

_podman_login() {
    echo
    echo -n 'Podman: '
    JENKINS_USERNAME_PWD_FILE="${SECRET_FILE_DIR}/$(__get_pull_secret_id jenkins)"
    local JENKINS_USERNAME=$(jq -r .username ${JENKINS_USERNAME_PWD_FILE})
    local JENKINS_PASSWORD=$(jq -r .password ${JENKINS_USERNAME_PWD_FILE})
    if [[ ! -z ${JENKINS_USERNAME} || ! -z ${JENKINS_PASSWORD} ]]
    then
        set -e
        podman login --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} -u ${JENKINS_USERNAME} -p ${JENKINS_PASSWORD} ${JENKINS_IMAGE_REGISTRY}
        set +e
    else
        echo "ERROR: UNABLE TO FIND JENKINS IMAGE REGISTRY USERNAME/PASSWORD FILE: ${SECRET_FILE_DIR}/${JENKINS_USERNAME_PWD_FILE}"
        exit 1
    fi
}

__get_pull_secret_id() {
    echo "${IMAGE_REGISTRY_PULL_SECRET_PREFIX}${1@L}${IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
}