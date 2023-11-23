#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export GITHUB_REST_API_HDR='Accept: application/vnd.github.v3+json'

_verify_scm_secret_files_exist() {
    if [[ ! -f ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} ||
          ! -f ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub ||
          ! -f ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} ||
          ! -f ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub ||
          ! -f ${EL_CICD_OCI_SECRETS_FILE} ||
          ! -f ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE} ]]
    then
        echo
        echo "ERROR:"
        echo "  MISSING ONE OR MORE OF THE FOLLOWING CREDENTIALS FILES:"
        echo "    ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}.pub"
        echo "    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}.pub"
        echo "    ${EL_CICD_OCI_SECRETS_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE//${SECRET_FILE_DIR}\//}"

        __verify_continue
    fi
}

_get_oci_registry_ids() {
    local OCI_REGISTRY_IDS="${JENKINS} "
    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then
        OCI_REGISTRY_IDS+="${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS/:/ } "
    fi
    
    OCI_REGISTRY_IDS+="${PRE_PROD_ENV} "
    
    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        OCI_REGISTRY_IDS+="${PROD_ENV}"
    fi
    
    echo ${OCI_REGISTRY_IDS}
}

_verify_oci_registry_secrets() {
    local OCI_REGISTRY_IDS=$(_get_oci_registry_ids)
    
    for OCI_REGISTRY_ID in ${OCI_REGISTRY_IDS@L}
    do
        _oci_registry_login ${OCI_REGISTRY_ID}
    done
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

_oci_registry_login() {
    OCI_REGISTRY_ID=${1}
    
    local OCI_USERNAME=$(_get_oci_username ${OCI_REGISTRY_ID})
    local OCI_PASSWORD=$(_get_oci_password ${OCI_REGISTRY_ID})
    local OCI_REGISTRY=${OCI_REGISTRY_ID@U}${OCI_REGISTRY_POSTFIX}
    local ENABLE_TLS=${OCI_REGISTRY_ID@U}${OCI_ENABLE_TLS_POSTFIX}
    
    if [[ "${OCI_USERNAME}" && "${OCI_PASSWORD}" && "${!ENABLE_TLS}" && "${!OCI_REGISTRY}" ]]
    then
        set -e
        echo
        echo -n "LOGIN TO ${OCI_REGISTRY_ID@U} OCI REGISTRY [${!OCI_REGISTRY}]: "
        echo ${OCI_PASSWORD} | podman login ${!OCI_REGISTRY} --tls-verify=${!ENABLE_TLS} -u ${OCI_USERNAME} --password-stdin 
        set +e
    else
        PARENT_DIR=$(basename $(dirname ${EL_CICD_OCI_SECRETS_FILE}))
        echo "ERROR: ONE OF THE FOLLOWING NOT DEFINED FOR THE ${OCI_REGISTRY_ID@U} OCI REGISTRY:"
        echo "- USERNAME/PASSWORD in ${PARENT_DIR}/$(basename ${EL_CICD_OCI_SECRETS_FILE})"
        echo "- OCI_REGISTRY: ${!OCI_REGISTRY}"
        exit 1
    fi
}

_oci_helm_registry_login() {    
    local HELM_REGISTRY_USERNAME=$(_get_oci_username ${HELM})
    local HELM_REGISTRY_PASSWORD=$(_get_oci_password ${HELM})
    
    if [[ EL_CICD_HELM_OCI_REGISTRY_ENABLE_TLS == ${_FALSE} ]]
    then 
        local TLS_INSECURE='--insecure'
    fi
    
    if [[ "${HELM_REGISTRY_USERNAME}" && "${HELM_REGISTRY_PASSWORD}" ]]
    then
        set -e
        echo
        echo -n "LOGIN TO HELM OCI REGISTRY [${EL_CICD_HELM_OCI_REGISTRY_DOMAIN}]: "
        echo ${HELM_REGISTRY_PASSWORD} | \
            helm registry login ${EL_CICD_HELM_OCI_REGISTRY_DOMAIN} ${TLS_INSECURE} -u ${HELM_REGISTRY_USERNAME} --password-stdin 
        set +e
    else
        PARENT_DIR=$(basename $(dirname ${EL_CICD_OCI_SECRETS_FILE}))
        echo "ERROR: ONE OF THE FOLLOWING NOT DEFINED FOR THE HELM OCI REGISTRY:"
        echo "- USERNAME/PASSWORD in ${PARENT_DIR}/$(basename ${EL_CICD_OCI_SECRETS_FILE})"
        echo "- EL_CICD_HELM_OCI_REGISTRY_DOMAIN: ${EL_CICD_HELM_OCI_REGISTRY_DOMAIN}"
        exit 1
    fi
}

_oci_jenkins_registry_login() {
    _oci_registry_login ${JENKINS}
}

_get_oci_username() {
    jq -r ".${1}.username | select (.!=null)" ${EL_CICD_OCI_SECRETS_FILE}
}

_get_oci_password() {
    jq -r ".${1}.password | select (.!=null)" ${EL_CICD_OCI_SECRETS_FILE}
}