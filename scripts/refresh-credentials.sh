#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

CLI_OPTIONS=${1}

if [[ ${CLI_OPTIONS} == '--non-prod' ]]
then
    _install_sealed_secrets ${EL_CICD_MASTER_NAMESPACE}

    EL_CICD_CREDS_SH_SCRIPT=./scripts/el-cicd-non-prod-credentails.sh
elif [[ ${CLI_OPTIONS} == '--prod' ]]
then
    _install_sealed_secrets ${EL_CICD_PROD_MASTER_NAMEPACE}

    EL_CICD_CREDS_SH_SCRIPT=./scripts/el-cicd-prod-credentails.sh
fi

eval ${EL_CICD_CREDS_SH_SCRIPT}

