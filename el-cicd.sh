#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

EL_CICD_SYSTEM_CONFIG_FILE=${1}

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

read -r -d '' HELP_MSG << EOM
Bootstraps and/or pushes credentials to an el-CICD Onboarding Automation Server

Options:
    -N,   --non-prod:   bootstraps Non-Prod el-CICD Onboarding Automation Server
    -P,   --prod:       bootstraps Prod el-CICD Onboarding Automation Server
          --np-creds:   push credentials to a  Non-Prod el-CICD Onboarding Automation Server
          --pr-creds:   push credentials to a  Non-Prod el-CICD Onboarding Automation Server
EOM

cd "$(dirname "${0}")"

echo 'Loading el-CICD environment...'

set -o allexport

BOOTSTRAP_DIR=$(pwd)

SCRIPTS_DIR=${BOOTSTRAP_DIR}/scripts

RESOURCES_DIR=${BOOTSTRAP_DIR}/resources
BUILD_CONFIGS_DIR=${RESOURCES_DIR}/buildconfigs
TEMPLATES_DIR=${RESOURCES_DIR}/templates

CONFIG_REPOSITORY=${BOOTSTRAP_DIR}/../el-CICD-config
CONFIG_REPOSITORY_BOOTSTRAP=${CONFIG_REPOSITORY}/bootstrap
CONFIG_REPOSITORY_JENKINS=${CONFIG_REPOSITORY}/jenkins

source ${CONFIG_REPOSITORY}/${EL_CICD_SYSTEM_CONFIG_FILE}

source ${SCRIPTS_DIR}/bootstrap-functions-defs.sh
source ${SCRIPTS_DIR}/credential-functions.sh

set +o allexport

echo
echo 'el-CICD environment loaded'

echo
if [[ ${1} == '--non-prod' || ${1} == '-N' ]]
then
    echo "BOOTSTRAPPING NON-PROD"
    EL_CICD_SH_SCRIPT=./scripts/el-cicd-non-prod-bootstrap.sh
elif [[ ${1} == '--prod' || ${1} == '-P' ]]
then
    echo "BOOTSTRAPPING PROD"
    EL_CICD_SH_SCRIPT=./scripts/el-cicd-prod-bootstrap.sh
elif [[ ${1} == '--np-creds' ]]
then
    echo "REFRESH NON-PROD CREDENTIALS"
    EL_CICD_SH_SCRIPT='./scripts/refresh-credentials.sh --non-prod'
elif [[ ${1} == '--pr-creds' ]]
then
    echo "REFRESH PROD CREDENTIALS"
    EL_CICD_SH_SCRIPT='./scripts/refresh-credentials.sh --prod'
elif [[ ${1} == '--np-jenkins' ]]
then
    echo "UPDATE NON_PROD JENKINS IMAGE"
    _build_el_cicd_jenkins_image ${JENKINS_NON_PROD_IMAGE_STREAM} non-prod-jenkins-casc.yml  non-prod-plugins.txt Y
elif [[ ${1} == '--pr-jenkins' ]]
then
    echo "UPDATE PROD JENKINS IMAGE"
    _build_el_cicd_jenkins_image ${JENKINS_PROD_IMAGE_STREAM} prod-jenkins-casc.yml  prod-plugins.txt Y
elif [[ ${1} == '--help' ]]
then
    echo "${HELP_MSG}"
    exit 0
else 
    echo "ERROR: Unknown or missing option"
    echo
    echo "${HELP_MSG}"
    exit 1
fi

eval ${EL_CICD_SH_SCRIPT} ${EL_CICD_SYSTEM_CONFIG_FILE}
