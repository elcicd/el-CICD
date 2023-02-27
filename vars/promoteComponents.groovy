/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the component promotion pipeline.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select components to promote and remove') {
        loggingUtils.echoBanner("SELECT ENVIRONMENT TO PROMOTE TO AND COMPONENTS TO DEPLOY OR REMOVE")

        promotionUtils.getUserPromotionRemovalSelections(projectInfo)
    }
    
    promotionUtils.runVerifyImagesExistStages(projectInfo, projectInfo.componentsToPromote)

    stage('Verify images are deployed in previous environment, collect source commit hash') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE ARE DEPLOYED IN ${projectInfo.deployFromEnv}",
                                projectInfo.componentsToPromote.collect { it.name }.join(', '))

        promotionUtils.verifyDeploymentsInPreviousEnv(projectInfo)
    }

    concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.componentsToPromote) { component ->
        def checkDeployBranchScript = "git show-branch refs/remotes/origin/${component.deploymentBranch} || : | tr -d '[:space:]'"
        component.deployBranchExists = sh(returnStdout: true, script: checkDeployBranchScript)
        component.deployBranchExists = !component.deployBranchExists.isEmpty()

        def scmBranch = component.deployBranchExists ? component.deploymentBranch : component.previousDeploymentBranch
        if (scmBranch) {
            sh "git checkout ${scmBranch}"
        }

        component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
    }

    promotionUtils.runPromoteImagesStages(projectInfo)

    stage('Create deployment branches, if necessary') {
        loggingUtils.echoBanner("CREATE DEPLOYMENT BRANCH(ES) FOR PROMOTION RELEASE:",
                                projectInfo.componentsToPromote.collect { it. name }.join(', '))

        promotionUtils.createDeploymentBranches(projectInfo)
    }

    deployComponents(projectInfo: projectInfo,
                     components: projectInfo.componentsToPromote,
                     componentsToRemove: projectInfo.componentsToRemove,
                     imageTag: projectInfo.deployToEnv)
}
