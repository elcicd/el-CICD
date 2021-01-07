#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

read -r -d '' HELP_MSG << EOM
Bootstraps and/or pushes credentials to an el-CICD Onboarding Automation Server

Options:
    -N,   --non-prod:   bootstraps Non-Prod el-CICD Onboarding Automation Server
    -P,   --prod:       bootstraps Prod el-CICD Onboarding Automation Server
          --np-creds:   push credentials to a  Non-Prod el-CICD Onboarding Automation Server
          --pr-creds:   push credentials to a  Non-Prod el-CICD Onboarding Automation Server
EOM

set -o allexport

BOOTSTRAP_DIR=$(pwd)
SCRIPTS_DIR=${BOOTSTRAP_DIR}/scripts

set -o allexport


if [[ $1 == '--non-prod' || $1 == '-N' ]]
then
    START_MSG="BOOTSTRAPPING NON-PROD"
    EL_CICD_SH_SCRIPT=./scripts/el-cicd-non-prod-bootstrap.sh
elif [[ $1 == '--prod' || $1 == '-P' ]]
then
    START_MSG="BOOTSTRAPPING PROD"
    EL_CICD_SH_SCRIPT=./scripts/el-cicd-prod-bootstrap.sh
elif [[ $1 == '--np-creds' ]]
then
    START_MSG="REFRESH NON-PROD CREDENTIALS"
    EL_CICD_SH_SCRIPT='./scripts/el-cicd-non-prod-credentials.sh -s'
elif [[ $1 == '--pr-creds' ]]
then
    START_MSG="REFRESH PROD CREDENTIALS"
    EL_CICD_SH_SCRIPT='./scripts/el-cicd-prod-credentials.sh -s'
elif [[ $1 == '--help' ]]
then
    echo "${HELP_MSG}"
    exit 0
else 
    echo "ERROR: Unknown or missing option"
    echo
    echo "${HELP_MSG}"
    exit 1
fi

echo ${START_MSG}

echo
echo "==================================================================="
echo "WARNING:"
echo "   This script should only be run on Non-Prod or Prod bastion"
echo
echo "   WHEN USING THIS IN YOUR OWN CLUSTER:"
echo "       FORK THE el-CICD-config REPOSITORY FIRST AND CREATE YOUR OWN PUBLIC/KEYS AND CREDENTIALS AS NEEDED"
echo
echo "   ACCESS TO THE el-CICD NON-PROD AND PROD MASTER JENKINS SHOULD BE RESTRICTED TO CLUSTER ADMINS"
echo "==================================================================="
echo

cd "$(dirname "$0")"

echo 'Loading el-CICD environment...'

set -o allexport
BOOTSTRAP_DIR=$(pwd)
SCRIPTS_DIR=${BOOTSTRAP_DIR}/scripts

source ${SCRIPTS_DIR}/global-defs.sh
source ${SCRIPTS_DIR}/bootstrap-functions-defs.sh
source ${SCRIPTS_DIR}/credential-functions.sh
set +o allexport

echo
echo 'el-CICD environment loaded'

eval ${EL_CICD_SH_SCRIPT}
