#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

if [[ -z "${EL_CICD_NON_PROD_MASTER_NAMEPACE}" ]]
then
    echo "el-CICD non-prod master project must be defined in el-cicd-system.config"
    echo "Set the value of EL_CICD_NON_PROD_MASTER_NAMEPACE el-cicd-system.config to and rerun."
    echo "Exiting."
    exit 1
fi

_install_sealed_secrets ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

_confirm_wildcard_domain_for_cluster

_delete_namespace ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

_create_namespace_with_selectors ${EL_CICD_NON_PROD_MASTER_NAMEPACE} ${EL_CICD_NON_PROD_MASTER_NODE_SELECTORS}

_build_el_cicd_jenkins_image ${JENKINS_NON_PROD_IMAGE_STREAM} non-prod-jenkins-casc.yml  non-prod-plugins.txt

_create_onboarding_automation_server ${JENKINS_NON_PROD_IMAGE_STREAM} non-prod-jenkins-casc.yml ${EL_CICD_NON_PROD_MASTER_NAMEPACE}

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

if [[ ${JENKINS_SKIP_AGENT_BUILDS} != 'true' ]]
then
    HAS_BASE_AGENT=$(oc get --ignore-not-found is jenkins-agent-el-cicd-${JENKINS_AGENT_DEFAULT} -n openshift -o jsonpath='{.metadata.name}')
    if [[ -z ${HAS_BASE_AGENT} ]]
    then
        echo
        echo "Creating Jenkins Agents"
        oc start-build create-all-jenkins-agents -n ${EL_CICD_NON_PROD_MASTER_NAMEPACE}
        echo "Started 'create-all-jenkins-agents' job on Non-prod Onboarding Automation Server"
    else 
        echo
        echo "Base agent found: to manually rebuild Jenkins Agents, run the 'create-all-jenkins-agents' job"
    fi
else
    echo
    echo "JENKINS_SKIP_AGENT_BUILDS=true.  Jenkins agent builds skipped."
fi

echo 
echo 'Non-prod Onboarding Server Bootstrap Script Complete'
