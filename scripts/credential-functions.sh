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

    if [[ "${PULL_SECRET_FILES}" ]]
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
    if [[ "${HAS_SEALED_SECRETS}" ]]
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
    helm upgrade --install --atomic sealed-secrets --history-max 2 \
                 --set-string fullnameOverride=sealed-secrets-controller \
                 --repo https://bitnami-labs.github.io/sealed-secrets \
                 --version ${SEALED_SECRETS_CHART_VERSION} \
                 -n kube-system \
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

_podman_login() {
    echo
    echo "Podman login to Jenkins image registry [${JENKINS_IMAGE_REGISTRY}]:"
    JENKINS_USERNAME_PWD_FILE="${SECRET_FILE_DIR}/$(__get_pull_secret_id jenkins)"
    local JENKINS_USERNAME=$(jq -r .username ${JENKINS_USERNAME_PWD_FILE})
    local JENKINS_PASSWORD=$(jq -r .password ${JENKINS_USERNAME_PWD_FILE})
    if [[ ! -z ${JENKINS_USERNAME} || "${JENKINS_PASSWORD}" ]]
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