#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_refresh_credentials() {
    set -e
    rm -rf ${SECRET_FILE_TEMP_DIR}
    mkdir -p ${SECRET_FILE_TEMP_DIR}

    echo
    echo "Adding read only deploy key for el-CICD"
    _push_deploy_key_to_github el-CICD ${EL_CICD_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    echo
    echo "Adding read only deploy key for el-CICD-config"
    _push_deploy_key_to_github el-CICD-config ${EL_CICD_CONFIG_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}

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
