#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

if [[ -z "${EL_CICD_NON_PROD_MASTER_NAMEPACE}" ]]
then
    echo "el-CICD non-prod master project must be defined in el-cicd-system.config"
    echo "Set the value of EL_CICD_NON_PROD_MASTER_NAMEPACE el-cicd-system.config to and rerun."
    echo "Exiting."
    exit 1
fi

_collect_and_verify_bootstrap_actions_with_user ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

_run_el_cicd_bootstrap ${EL_CICD_NON_PROD_MASTER_NAMEPACE} \
                       ${EL_CICD_NON_PROD_MASTER_NODE_SELECTORS} \
                       ${JENKINS_NON_PROD_IMAGE_STREAM} \
                       non-prod-jenkins-casc.yml  \
                       non-prod-plugins.txt

echo
echo "Creating the Non-Prod Onboarding Automation Server pipelines: ${JENKINS_SKIP_AGENT_BUILDS}"
if [[ ${JENKINS_SKIP_AGENT_BUILDS} == 'true' ]]
then
    PIPELINE_TEMPLATES=('non-prod-project-onboarding' 'non-prod-project-delete')
else
    PIPELINE_TEMPLATES=('non-prod-project-onboarding' 'non-prod-project-delete' 'create-all-jenkins-agents')
fi

for PIPELINE_TEMPLATE in ${PIPELINE_TEMPLATES[@]}
do
    oc process -f ${BUILD_CONFIGS_DIR}/${PIPELINE_TEMPLATE}-pipeline-template.yml \
            -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} -n ${EL_CICD_NON_PROD_MASTER_NAMEPACE} | \
        oc apply -f - -n ${EL_CICD_NON_PROD_MASTER_NAMEPACE}
done

echo
echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS'
${SCRIPTS_DIR}/el-cicd-non-prod-credentials.sh

echo
echo "RUN ALL CUSTOM SCRIPTS 'non-prod-*.sh' FOUND IN ${CONFIG_REPOSITORY_BOOTSTRAP}"
${SCRIPTS_DIR}/el-cicd-run-custom-config-scripts.sh ${CONFIG_REPOSITORY_BOOTSTRAP} non-prod

_build_jenkins_agents ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

echo 
echo 'Non-prod Onboarding Server Bootstrap Script Complete'
