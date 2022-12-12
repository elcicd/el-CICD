/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo
    
    cicdJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, false)

    stage('refresh automation pipelines') {        
        def pipelineFiles
        dir(el.cicd.PROD_AUTOMATION_PIPELINES_DIR) {
            pipelineFiles = findFiles(glob: "**/*.xml")
        }
        
        jenkinsUtils.createOrUpdatePipelines(projectInfo, el.cicd.PROD_AUTOMATION, el.cicd.PROD_AUTOMATION_PIPELINES_DIR, pipelineFiles)
    }

    onboardingUtils.createNfsPersistentVolumes(projectInfo, false)

    stage('Setup openshift namespace environments') {
        loggingUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", projectInfo.prodNamespace)

        def nodeSelectors = el.cicd["${el.cicd.PROD_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]

        onboardingUtils.createNamepace(projectInfo, projectInfo.prodNamespace, projectInfo.prodEnv, nodeSelectors)

        onboardingUtils.copyPullSecretsToEnvNamespace(projectInfo.prodNamespace, projectInfo.prodEnv)

        def resourceQuotaFile = projectInfo.resourceQuotas[projectInfo.prodEnv] ?: projectInfo.resourceQuotas.default
        onboardingUtils.applyResoureQuota(projectInfo, projectInfo.prodNamespace, resourceQuotaFile)
    }

    manageCicdCredentials([projectInfo: projectInfo, isNonProd: false])
}