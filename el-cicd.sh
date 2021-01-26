#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

CLI_OPTION=${1}

cd "$(dirname "${0}")"

echo 'Loading el-CICD environment...'

set -o allexport

EL_CICD_SYSTEM_CONFIG_FILE=${2}

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
source ${SCRIPTS_DIR}/jenkins-builder-functions.sh
source ${SCRIPTS_DIR}/credential-functions.sh

set +o allexport

echo
echo 'el-CICD environment loaded'

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
Usage: el-cicd.sh [OPTION] [config-file]

Bootstraps and/or pushes credentials to an el-CICD Onboarding Automation Server

Options:
    -N,   --non-prod:        bootstraps Non-Prod el-CICD Onboarding Automation Server
    -P,   --prod:            bootstraps Prod el-CICD Onboarding Automation Server
          --np-creds:        push credentials to a  Non-Prod el-CICD Onboarding Automation Server
          --pr-creds:        push credentials to a  Non-Prod el-CICD Onboarding Automation Server
          --jenkins:         build el-CICD Jenkins image
          --jenkins-agents:  build el-CICD Jenkins agent images

config-file: file name or path relative the root of the sibling directory el-CICD-config
EOM

if [[ -z ${EL_CICD_SYSTEM_CONFIG_FILE} ]]
then
    echo "ERROR: Unknown or missing config-file"
    echo
    echo "${HELP_MSG}"
    exit 1
fi

echo
if [[ ${CLI_OPTION} == '--non-prod' || ${CLI_OPTION} == '-N' ]]
then
    echo "BOOTSTRAPPING NON-PROD"
    _bootstrap_el_cicd non-prod
elif [[ ${CLI_OPTION} == '--prod' || ${CLI_OPTION} == '-P' ]]
then
    echo "BOOTSTRAPPING PROD"
    _bootstrap_el_cicd prod
elif [[ ${CLI_OPTION} == '--np-creds' ]]
then
    echo "REFRESH NON-PROD CREDENTIALS"
    _refresh_credentials non-prod
elif [[ ${CLI_OPTION} == '--pr-creds' ]]
then
    echo "REFRESH PROD CREDENTIALS"
    _refresh_credentials prod
elif [[ ${CLI_OPTION} == '--jenkins' ]]
then
    echo "UPDATE JENKINS IMAGE"
    _build_el_cicd_jenkins_image
elif [[ ${CLI_OPTION} == '--help' ]]
then
    echo "${HELP_MSG}"
    exit 0
else 
    echo "ERROR: Unknown or missing option"
    echo
    echo "${HELP_MSG}"
    exit 1
fi
