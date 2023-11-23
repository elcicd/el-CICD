#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_refresh_el_cicd_credentials() {
    set -e
    
    __create_el_cicd_scm_readonly_deploy_keys

    GIT_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE})
    _delete_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                                ${EL_CICD_ORGANIZATION} \
                                ${EL_CICD_REPO} \
                                ${GIT_ACCESS_TOKEN} \
                                ${EL_CICD_MASTER_NAMESPACE}
    
    _add_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                             ${EL_CICD_ORGANIZATION} \
                             ${EL_CICD_REPO} \
                             ${GIT_ACCESS_TOKEN} \
                             ${EL_CICD_MASTER_NAMESPACE} \
                             ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    _delete_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                                ${EL_CICD_ORGANIZATION} \
                                ${EL_CICD_CONFIG_REPO} \
                                ${GIT_ACCESS_TOKEN} \
                                ${EL_CICD_MASTER_NAMESPACE}

    _add_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                             ${EL_CICD_ORGANIZATION} \
                             ${EL_CICD_CONFIG_REPO} \
                             ${GIT_ACCESS_TOKEN} \
                             ${EL_CICD_MASTER_NAMESPACE} \
                             ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    
    __create_jenkins_secrets
   
    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then        
        _run_custom_config_script credentials-non-prod.sh
    fi
    
    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        _run_custom_config_script credentials-prod.sh
    fi

    echo 
    echo '--> el-CICD Master credentials refresh complete'
    set +e
}

__create_el_cicd_scm_readonly_deploy_keys() {
    mkdir -p ${SECRET_FILE_DIR}

    echo
    echo 'Creating el-CICD read-only Git repository ssh key files:'
    echo ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    local __FILE="${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    ssh-keygen -b 2048 -t rsa -f "${__FILE}" -q -N '' 2>/dev/null <<< y >/dev/null

    echo
    echo 'Creating el-CICD-config read-only Git repository ssh key files:'
    echo ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    local __FILE="${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    ssh-keygen -b 2048 -t rsa -f "${__FILE}" -q -N '' 2>/dev/null <<< y >/dev/null
}

__create_jenkins_secrets() {
    local OCI_REGISTRY_IDS=$(_get_oci_registry_ids)

    local SET_FLAGS=$(__create_image_registry_values_flags ${OCI_REGISTRY_IDS})
    
	if [[ "${EL_CICD_MASTER_NONPROD}" && "$(ls -A ${BUILD_SECRETS_FILE_DIR})" ]]
    then
        local PROFILE_FLAG="--set-string elCicdProfiles={builder-secrets}"
        SET_FLAGS+="${SET_FLAGS:+ }$(__create_builder_secret_flags)"
    fi

    echo
    echo "Creating all Jenkins secrets..."
    echo
    OCI_REGISTRY_IDS=$(echo ${OCI_REGISTRY_IDS@L} | sed -e 's/\s\+/,/g')
    local GIT_REPO_KEYS="${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID},${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}"
    set -e
    helm upgrade --install --atomic --history-max=1 --create-namespace \
        ${PROFILE_FLAG}  \
        --set-string elCicdDefs.BUILD_SECRETS_NAME=${EL_CICD_BUILD_SECRETS_NAME} \
        --set-string elCicdDefs.OCI_REGISTRY_IDS="{${OCI_REGISTRY_IDS}}" \
        --set-string elCicdDefs.GIT_REPO_SSH_KEY_IDS="{${GIT_REPO_KEYS}}" \
        --set-file elCicdDefs-${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.GIT_REPO_SSH_KEY=${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-file elCicdDefs-${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.GIT_REPO_SSH_KEY=${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-string elCicdDefs.GIT_ACCESS_TOKEN_ID=${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID} \
        --set-file elCicdDefs.GIT_ACCESS_TOKEN=${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE} \
        ${SET_FLAGS} \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/elcicd-jenkins-secrets-values.yaml \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        elcicd-jenkins-secrets \
        ${EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    set +e
}

__create_image_registry_values_flags() {
    local OCI_REGISTRY_IDS=${1}
    
    for OCI_REGISTRY_ID in ${OCI_REGISTRY_IDS@L}
    do
        local OCI_USERNAME=$(_get_oci_username ${OCI_REGISTRY_ID})
        local OCI_PASSWORD=$(_get_oci_password ${OCI_REGISTRY_ID})
        
        local SET_FLAGS+="${SET_FLAGS:+ }--set-string elCicdDefs-${OCI_REGISTRY_ID}.REGISTRY_USERNAME=${OCI_USERNAME}"
        SET_FLAGS+="${SET_FLAGS:+ }--set-string elCicdDefs-${OCI_REGISTRY_ID}.REGISTRY_PASSWORD=${OCI_PASSWORD}"
        
        local REGISTRY_URL=${OCI_REGISTRY_ID@U}${OCI_REGISTRY_POSTFIX}
        SET_FLAGS+="${SET_FLAGS:+ }--set-string elCicdDefs-${OCI_REGISTRY_ID}.REGISTRY_URL=${!REGISTRY_URL}"
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

