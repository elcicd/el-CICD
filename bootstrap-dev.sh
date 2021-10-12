#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

cd "$(dirname ${0})"

BOOTSTRAP_DIR=$(pwd)

EL_CICD_HOME=$(dirname ${BOOTSTRAP_DIR})

CONFIG_DIR=${EL_CICD_HOME}/el-CICD-config

RESOURCES_DIR=${BOOTSTRAP_DIR}/scripts/resources

source ${BOOTSTRAP_DIR}/scripts/bootstrap-functions.sh
source ${BOOTSTRAP_DIR}/scripts/bootstrap-dev-functions.sh

source ${CONFIG_DIR}/bootstrap/el-cicd-default-system.conf
source ${CONFIG_DIR}/bootstrap/el-cicd-default-bootstrap.conf
source ${CONFIG_DIR}/bootstrap/el-cicd-bootstrap-dev-env.conf

set -e
if [[ $1 == '--setup' ]]
then
    __bootstrap_dev_environment
elif [[ $1 == '--remove' ]]
then
    __remove_dev_environment
elif [[ $1 == '--remove-nexus' ]]
then
    __remove_nexus3
elif [[ $1 == '--remove-nexus-full' ]]
then
    __remove_nexus3

    __remove_nexus3_nfs_share
elif [[ $1 == '--create-credentials' ]]
then
    __create_credentials
else
    echo 'You must specify one of the following flags:'
    echo '    --setup          Sets up el-CICD developer environment'
    echo '    --remove -nexus  Removes el-CICD developer environment and Nexus3 NFS share'
    echo '    --create-creds   Only generate credentials'
fi