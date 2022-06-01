/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo
    
    jenkinsUtils.configureTeamJenkinsUrls(projectInfo)

    stage('refresh automation pipelines') {
        def pipelineFiles
        dir(el.cicd.PROD_AUTOMATION_PIPELINES_DIR) {
            pipelineFiles = findFiles(glob: "**/*.xml")
        }
        
        jenkinsUtils.createOrUpdatePipelines(el.cicd.PROD_AUTOMATION, pipelineFiles)
    }

    dir (el.cicd.EL_CICD_DIR) {
        git url: el.cicd.EL_CICD_GIT_REPO,
            branch: el.cicd.EL_CICD_CONFIG_GIT_REPO_BRANCH_NAME,
            credentialsId: el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    }
    verticalJenkinsCreationUtils.refreshGeneralAutomationPipelines(projectInfo, false)

    onboardingUtils.createNfsPersistentVolumes(projectInfo, false)

    stage('Setup openshift namespace environments') {
        loggingUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", projectInfo.prodNamespace)

        def nodeSelectors = el.cicd["${el.cicd.PROD_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]

        onboardingUtils.createNamepace(projectInfo, projectInfo.prodNamespace, projectInfo.prodEnv, nodeSelectors)

        onboardingUtils.copyPullSecretsToEnvNamespace(projectInfo.prodNamespace, projectInfo.prodEnv)

        def resourceQuotaFile = projectInfo.resourceQuotas[projectInfo.prodEnv] ?: projectInfo.resourceQuotas.default
        onboardingUtils.applyResoureQuota(projectInfo, projectInfo.prodNamespace, resourceQuotaFile)
    }

    stage('Delete old github public keys with curl') {
        githubUtils.deleteSshKeys(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        onboardingUtils.createAndPushPublicPrivateSshKeys(projectInfo)
    }
}