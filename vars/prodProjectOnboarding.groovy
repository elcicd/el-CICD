/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    verticalJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, false)

    def pipelines = el.getProdPipelines()
    verticalJenkinsCreationUtils.refreshSharedPipelines(projectInfo, pipelines)

    onboardingUtils.createNfsPersistentVolumes(projectInfo, false)

    stage('Setup openshift namespace environments') {
        pipelineUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", projectInfo.prodNamespace)

        def nodeSelectors = el.cicd["${el.cicd.PROD_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]

        onboardingUtils.createNamepace(projectInfo, projectInfo.prodNamespace, projectInfo.prodEnv, nodeSelectors)

        credentialUtils.copyPullSecretsToEnvNamespace(projectInfo.prodNamespace, projectInfo.prodEnv)

        def resourceQuotaFile = projectInfo.resourceQuotas[projectInfo.prodEnv] ?: projectInfo.resourceQuotas.default
        onboardingUtils.applyResoureQuota(projectInfo, projectInfo.prodNamespace, resourceQuotaFile)
    }

    stage('Delete old github public keys with curl') {
        credentialUtils.deleteDeployKeysFromGithub(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        credentialUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
    }
}