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

    if (projectInfo.componentsToPromote) {
        def verifedMsgs = ["IMAGE(s) VERIFED TO EXIST IN THE ${projectInfo.ENV_FROM} IMAGE REPOSITORY:"]
        def errorMsgs = ["MISSING IMAGE(s) IN THE ${projectInfo.ENV_FROM} IMAGE REPOSITORY:"]
        
        promotionUtils.runVerifyImagesInRegistryStages(projectInfo, verifedMsgs, errorMsgs)

        if (verifedMsgs.size() > 1) {
            loggingUtils.echoBanner(verifedMsgs)
        }

        if (errorMsgs.size() > 1) {
            loggingUtils.errorBanner(errorMsgs)
        }

        stage('Verify images are deployed in previous environment, collect source commit hash') {
            loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE ARE DEPLOYED IN ${projectInfo.deployFromEnv}",
                                     projectInfo.componentsToPromote.collect { it.name }.join(', '))

            promotionUtils.verifyDeploymentsInPreviousEnv(projectInfo)
        }

        loggingUtils.echoBanner("CLONE COMPONENT REPOSITORIES:", projectInfo.componentsToPromote.collect { it. name }.join(', '))

        promotionUtils.runCloneComponentReposStages(projectInfo)

        loggingUtils.echoBanner("COMPONENT REPOSITORY CLONING COMPLETE")

        loggingUtils.echoBanner("PROMOTE IMAGES FROM ${projectInfo.deployFromNamespace} ENVIRONMENT TO ${projectInfo.deployToNamespace} ENVIRONMENT FOR:",
                                projectInfo.componentsToPromote.collect { it. name }.join(', '))

        promotionUtils.runPromoteImagesToNextRegistryStages(projectInfo)

        stage('Create deployment branch if necessary') {
            loggingUtils.echoBanner("CREATE DEPLOYMENT BRANCH(ES) FOR PROMOTION RELEASE:",
                                     projectInfo.componentsToPromote.collect { it. name }.join(', '))

            promotionUtils.createDeploymentBranches(projectInfo)
        }
    }
    else {
        loggingUtils.echoBanner("NO COMPONENTS TO PROMOTE: SKIPPING")
    }

    deployComponents(projectInfo: projectInfo,
                     components: projectInfo.componentsToPromote,
                     componentsToRemove: projectInfo.componentsToRemove,
                     imageTag: projectInfo.deployToEnv)
}
