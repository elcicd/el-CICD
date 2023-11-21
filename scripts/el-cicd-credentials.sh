#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_refresh_el_cicd_credentials() {
    set -e
    
    __create_el_cicd_scm_readonly_deploy_keys

    SCM_ACCESS_TOKEN=$(cat ${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE})
    _delete_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                                ${EL_CICD_ORGANIZATION} \
                                ${EL_CICD_REPO} \
                                ${SCM_ACCESS_TOKEN} \
                                ${EL_CICD_MASTER_NAMESPACE}
    
    _add_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                             ${EL_CICD_ORGANIZATION} \
                             ${EL_CICD_REPO} \
                             ${SCM_ACCESS_TOKEN} \
                             ${EL_CICD_MASTER_NAMESPACE} \
                             ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    _delete_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                                ${EL_CICD_ORGANIZATION} \
                                ${EL_CICD_CONFIG_REPO} \
                                ${SCM_ACCESS_TOKEN} \
                                ${EL_CICD_MASTER_NAMESPACE}

    _add_scm_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                             ${EL_CICD_ORGANIZATION} \
                             ${EL_CICD_CONFIG_REPO} \
                             ${SCM_ACCESS_TOKEN} \
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
    helm upgrade --install --atomic --history-max=1 --create-namespace \
        ${PROFILE_FLAG}  \
        --set-string elCicdDefs.BUILD_SECRETS_NAME=${EL_CICD_BUILD_SECRETS_NAME} \
        --set-string elCicdDefs.PULL_SECRET_NAMES="{${PULL_SECRET_NAMES}}" \
        --set-string elCicdDefs.SCM_REPO_SSH_KEY_IDS="{${GIT_REPO_KEYS}}" \
        --set-file elCicdDefs-${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.SCM_REPO_SSH_KEY=${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-file elCicdDefs-${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.SCM_REPO_SSH_KEY=${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-string elCicdDefs.SCM_ACCESS_TOKEN_ID=${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_ID} \
        --set-file elCicdDefs.SCM_ACCESS_TOKEN=${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE} \
        ${SET_FLAGS} \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/el-cicd-jenkins-secrets-values.yaml \
        -n ${EL_CICD_MASTER_NAMESPACE} \
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

