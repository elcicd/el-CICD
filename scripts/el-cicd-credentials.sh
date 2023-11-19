#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_refresh_el_cicd_scm_credentials() {
    set -e
    rm -rf ${SECRET_FILE_TEMP_DIR}
    mkdir -p ${SECRET_FILE_TEMP_DIR}

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
   
    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then        
        _run_custom_config_script credentials-non-prod.sh
    fi
    
    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        _run_custom_config_script credentials-prod.sh
    fi

    rm -rf ${SECRET_FILE_TEMP_DIR}

    echo 
    echo 'Custom Onboarding Server Credentials Script(s) Complete'
    set +e
}
