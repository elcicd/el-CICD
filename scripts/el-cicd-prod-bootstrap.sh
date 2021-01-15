#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

if [[ -z "${EL_CICD_PROD_MASTER_NAMEPACE}" ]]
then
    echo "el-CICD prod master project must be defined in el-cicd-system.config"
    echo "Set the value of EL_CICD_PROD_MASTER_NAMEPACE el-cicd-system.config to and rerun."
    echo "Exiting."
    exit 1
fi

_bootstrap_el_cicd ${EL_CICD_PROD_MASTER_NAMEPACE}

echo
echo 'Creating the prod project onboarding pipeline'
oc process -f ./resources/buildconfigs/prod-project-onboarding-pipeline-template.yml \
    -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} -n ${EL_CICD_PROD_MASTER_NAMEPACE} \
  | oc create -f - -n ${EL_CICD_PROD_MASTER_NAMEPACE}

echo
echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS'
${SCRIPTS_DIR}/el-cicd-prod-credentials.sh

echo
echo "RUN ALL CUSTOM SCRIPTS 'prod-*.sh' FOUND IN ${CONFIG_REPOSITORY_BOOTSTRAP}"
${SCRIPTS_DIR}/el-cicd-run-custom-config-scripts.sh ${CONFIG_REPOSITORY_BOOTSTRAP} prod

_build_jenkins_agents ${EL_CICD_PROD_MASTER_NAMEPACE} true

echo
echo 'Prod Onboarding Server Bootstrap Script Complete'