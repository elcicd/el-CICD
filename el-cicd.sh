#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

set -o allexport

CLI_OPTION=${1}

EL_CICD_SYSTEM_CONFIG_FILE=${2}

_TRUE='true'
_FALSE='false'

BOOTSTRAP_DIR=$(pwd)

SCRIPTS_DIR=${BOOTSTRAP_DIR}/scripts

RESOURCES_DIR=${BOOTSTRAP_DIR}/resources
BUILD_CONFIGS_DIR=${RESOURCES_DIR}/buildconfigs
TEMPLATES_DIR=${RESOURCES_DIR}/templates

CONFIG_REPOSITORY=${BOOTSTRAP_DIR}/../el-CICD-config
CONFIG_REPOSITORY_BOOTSTRAP=${CONFIG_REPOSITORY}/bootstrap
CONFIG_REPOSITORY_JENKINS=${CONFIG_REPOSITORY}/jenkins

set +o allexport

read -r -d '' HELP_MSG << EOM
Usage: el-cicd.sh [OPTION] [config-file]

Bootstraps and/or pushes credentials to an el-CICD Onboarding Automation Server

Options:
    -N,   --non-prod:        bootstraps Non-Prod el-CICD Onboarding Automation Server
    -n,   --non-prod-creds:  refresh credentials for a Non-Prod el-CICD Onboarding Automation Server
    -P,   --prod:            bootstraps Prod el-CICD Onboarding Automation Server
    -p,   --prod-creds:      refresh credentials for a Prod el-CICD Onboarding Automation Server
    -c,   --cicd-creds:      run the refresh-credentials pipeline on the el-CICD Onboarding Automation Server
    -s,   --sealed-secrets:  reinstall/upgrade Sealed Secrets
    -j,   --jenkins:         only build el-CICD Jenkins image
    -a,   --agents:          only build el-CICD Jenkins agent images
    -A,   --jenkins-agents:  build el-CICD Jenkins and Jenkins agent images
          --help:            print el-CICD.sh help

config-file: file name or path relative the root of the sibling directory el-CICD-config
EOM

if [[ ${CLI_OPTION} == '--help' ]]
then
    echo "${HELP_MSG}"
    exit 0
elif [[ ! -f ${CONFIG_REPOSITORY}/${EL_CICD_SYSTEM_CONFIG_FILE} ]]
then
    echo "ERROR: Uknown or missing config-file: ${EL_CICD_SYSTEM_CONFIG_FILE}"
    echo
    echo "${HELP_MSG}"
    exit 1
fi

getopts

cd "$(dirname "${0}")"

echo 'Loading el-CICD environment...'

set -o allexport

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

echo
case ${CLI_OPTION} in

    '--non-prod' | '-N')
        echo "BOOTSTRAPPING NON-PROD"
        _bootstrap_el_cicd non-prod
    ;;

    '--prod' | '-P')
        echo "BOOTSTRAPPING PROD"
        _bootstrap_el_cicd prod
    ;;

    '--non-prod-creds' | '-n')
        echo "REFRESH NON-PROD CREDENTIALS"
        eval ./scripts/el-cicd-non-prod-credentials.sh
    ;;

    '--prod-creds' | '-p')
        echo "REFRESH PROD CREDENTIALS"
        eval ./scripts/el-cicd-prod-credentials.sh
    ;;

    '--cicd-creds' | '-c')
        echo "REFRESH ALL CICD SERVERS CREDENTIALS"

        echo
        echo "Refreshing all CICD Servers for all RBAC Groups in the cluster maanged by ${EL_CICD_MASTER_NAMESPACE}"
        oc start-build refresh-credentials --wait --follow -n ${EL_CICD_MASTER_NAMESPACE}
    ;;

    '--sealed-secrets' | '-s')
        echo "INSTALL SEALED SECRETS"
        _check_sealed_secrets

        if [[ $(is_true ${INSTALL_KUBESEAL})  == ${_TRUE} ]]
        then
            _install_sealed_secrets
        fi
    ;;

    '--jenkins' | '-j')
        echo "BUILD JENKINS IMAGE"
        _build_el_cicd_jenkins_imaged
    ;;

    '--agents' | '-a')
        echo "BUILD JENKINS AGENT IMAGES"
        _build_el_cicd_jenkins_agent_images_image
    ;;

    '--jenkins-agents' | '-A')
        echo "UPDATE JENKINS IMAGE AND BUILD JENKINS AGENT IMAGES"
        _build_el_cicd_jenkins_image

        _build_el_cicd_jenkins_agent_images_image
    ;;

    *)
        echo "ERROR: Unknown command option '${CLI_OPTION}'"
        echo
        echo "${HELP_MSG}"
        exit 1
    ;;
esac
