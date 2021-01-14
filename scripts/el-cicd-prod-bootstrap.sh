#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

if [[ -z "${EL_CICD_PROD_MASTER_NAMEPACE}" ]]
then
    echo "el-CICD prod master project must be defined in el-cicd-system.config"
    echo "Set the value of EL_CICD_PROD_MASTER_NAMEPACE el-cicd-system.config to and rerun."
    echo "Exiting."
    exit 1
fi

_install_sealed_secrets ${EL_CICD_PROD_MASTER_NAMEPACE}

_confirm_wildcard_domain_for_cluster

_delete_namespace ${EL_CICD_PROD_MASTER_NAMEPACE}

_create_namespace_with_selectors ${EL_CICD_PROD_MASTER_NAMEPACE} ${EL_CICD_PROD_MASTER_NODE_SELECTORS}

_build_el_cicd_jenkins_image ${JENKINS_PROD_IMAGE_STREAM} prod-jenkins-casc.yml  prod-plugins.txt

_create_onboarding_automation_server ${JENKINS_PROD_IMAGE_STREAM} prod-jenkins-casc.yml ${EL_CICD_PROD_MASTER_NAMEPACE}

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

_build_jenkins_agents ${EL_CICD_NON_PROD_MASTER_NAMEPACE} true

echo
echo 'Prod Onboarding Server Bootstrap Script Complete'