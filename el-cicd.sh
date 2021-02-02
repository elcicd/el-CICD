#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

read -r -d '' WARNING_MSG << EOM
===================================================================
WARNING:
This script should only be run on Non-Prod or Prod bastion

WHEN USING THIS IN YOUR OWN CLUSTER:
    FORK THE el-CICD-config REPOSITORY FIRST AND CREATE YOUR OWN PUBLIC/KEYS AND CREDENTIALS AS NEEDED

ACCESS TO THE el-CICD NON-PROD AND PROD MASTER JENKINS SHOULD BE RESTRICTED TO CLUSTER ADMINS
===================================================================
EOM

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

echo "${WARNING_MSG}"
sleep 2

cd "$(dirname "${0}")"

set -e -o allexport

BOOTSTRAP_DIR=$(pwd)

CLI_OPTION=${1}

EL_CICD_SYSTEM_CONFIG_FILE=${2}

CONFIG_REPOSITORY=${BOOTSTRAP_DIR}/../el-CICD-config

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

SCRIPTS_DIR=${BOOTSTRAP_DIR}/scripts

RESOURCES_DIR=${BOOTSTRAP_DIR}/resources
BUILD_CONFIGS_DIR=${RESOURCES_DIR}/buildconfigs
TEMPLATES_DIR=${RESOURCES_DIR}/templates

CONFIG_REPOSITORY_BOOTSTRAP=${CONFIG_REPOSITORY}/bootstrap
CONFIG_REPOSITORY_JENKINS=${CONFIG_REPOSITORY}/jenkins

TARGET_JENKINS_BUILD_DIR=../jenkins-target

_TRUE='true'
_FALSE='false'

_YES='Yes'
_NO='No'

echo
echo 'Loading el-CICD environment...'

echo
for FILE in bootstrap-functions.sh credential-functions.sh jenkins-builder-functions.sh
do
    echo "sourcing file: ${FILE}"
    source ${SCRIPTS_DIR}/${FILE}
done

echo
echo "SOURCING ROOT CONFIG FILE: ${EL_CICD_SYSTEM_CONFIG_FILE}"
source ${CONFIG_REPOSITORY}/${EL_CICD_SYSTEM_CONFIG_FILE}

echo
for FILE in $(echo "${INCLUDE_BOOTSTRAP_FILES}:${INCLUDE_SYSTEM_FILES}" | tr ':' ' ')
do
    echo "sourcing config file: ${FILE}"
    source ${CONFIG_REPOSITORY_BOOTSTRAP}/${FILE}
done

source ${CONFIG_REPOSITORY}/${EL_CICD_SYSTEM_CONFIG_FILE}

CLUSTER_API_HOSTNAME=$(oc whoami --show-server | awk -F '://' '{ print $2 }')

echo
echo 'el-CICD environment loaded'

set +o allexport

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

        _create_el_cicd_meta_info_config_map

        eval ./scripts/el-cicd-non-prod-credentials.sh
    ;;

    '--prod-creds' | '-p')
        echo "REFRESH PROD CREDENTIALS"

        _create_el_cicd_meta_info_config_map

        eval ./scripts/el-cicd-prod-credentials.sh
    ;;

    '--cicd-creds' | '-c')
        echo "REFRESH ALL CICD SERVERS CREDENTIALS"

        echo
        echo "Refreshing all CICD Servers in the cluster managed by ${ONBOARDING_MASTER_NAMESPACE}"
        oc start-build refresh-credentials --wait --follow -n ${ONBOARDING_MASTER_NAMESPACE}
    ;;

    '--sealed-secrets' | '-s')
        echo "INSTALL SEALED SECRETS"
        _check_sealed_secrets

        if [[ $(_is_true ${INSTALL_KUBESEAL})  == ${_TRUE} ]]
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
