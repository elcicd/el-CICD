#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

SERVER_TYPE=${1}

if [[ -z "${EL_CICD_MASTER_NAMESPACE}" ]]
then
    echo "el-CICD ${SERVER_TYPE} master project must be defined in ${EL_CICD_SYSTEM_CONFIG_FILE}"
    echo "Set the value of EL_CICD_MASTER_NAMESPACE ${EL_CICD_SYSTEM_CONFIG_FILE} to and rerun."
    echo "Exiting."
    exit 1
fi

if [[ ${SERVER_TYPE} == 'non-prod' ]]
then
    if [[ ${JENKINS_SKIP_AGENT_BUILDS} == 'true' ]]
    then
        PIPELINE_TEMPLATES='non-prod-project-onboarding non-prod-project-delete'
    else
        PIPELINE_TEMPLATES='non-prod-project-onboarding non-prod-project-delete create-all-jenkins-agents'
    fi
else
    PIPELINE_TEMPLATES='prod-project-onboarding-pipeline'
fi

_bootstrap_el_cicd "${PIPELINE_TEMPLATES}"

echo
echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS'
${SCRIPTS_DIR}/el-cicd-${SERVER_TYPE}-credentials.sh

echo
echo "RUN ALL CUSTOM SCRIPTS '${SERVER_TYPE}-*.sh' FOUND IN ${CONFIG_REPOSITORY_BOOTSTRAP}"
_run_custom_config_scripts ${SERVER_TYPE}

_build_jenkins_agents ${EL_CICD_MASTER_NAMESPACE}

echo 
echo "${SERVER_TYPE} Onboarding Server Bootstrap Script Complete"
