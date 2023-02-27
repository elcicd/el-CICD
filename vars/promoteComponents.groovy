/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the component promotion pipeline.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select components to promote and remove') {
        loggingUtils.echoBanner("SELECT COMPONENTS TO PROMOTE TO OR REMOVE FROM ${projectInfo.deployToEnv}")

        promotionUtils.getUserPromotionRemovalSelections(projectInfo)
    }
    
    stage('Verify image(s) exist in registry') {
        promotionUtils.runVerifyImagesExistStages(projectInfo, projectInfo.componentsToPromote)
    }

    stage('Verify image(s) are deployed in previous environment') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE ARE DEPLOYED IN ${projectInfo.deployFromEnv}",
                                projectInfo.componentsToPromote.collect { it.name }.join(', '))

        promotionUtils.verifyDeploymentsInPreviousEnv(projectInfo)
    }

    stage('Create and/or checkout deployment branches') {
        promotionUtils.createAndCheckoutDeploymentBranches(projectInfo)
    }
    
    stage('Promote images in registry') {
        promotionUtils.runPromoteImagesStages(projectInfo)
    }

    deployComponents(projectInfo: projectInfo,
                     componentsToDeploy: projectInfo.componentsToPromote,
                     componentsToRemove: projectInfo.componentsToRemove,
                     imageTag: projectInfo.deployToEnv)
}
