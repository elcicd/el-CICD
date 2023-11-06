/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the component promotion pipeline.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select components to promote and remove') {
        loggingUtils.echoBanner("SELECT ENVIRONMENT TO PROMOTE TO AND COMPONENTS TO DEPLOY OR REMOVE")

        promoteComponentsUtils.getUserPromotionRemovalSelections(projectInfo, args)
    }
    
    stage('Verify image(s) exist in registry') {
        promoteComponentsUtils.runVerifyImagesExistStages(projectInfo)
    }

    stage('Verify image(s) are deployed in previous environment') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE ARE DEPLOYED IN ${projectInfo.deployFromEnv}",
                                projectInfo.componentsToPromote.collect { it.name }.join(', '))

        promoteComponentsUtils.verifyDeploymentsInPreviousEnv(projectInfo)
    }

    stage('Create and/or checkout deployment branches') {
        promoteComponentsUtils.createAndCheckoutDeploymentBranches(projectInfo)
    }
    
    stage('Promote images in registry') {
        promoteComponentsUtils.runPromoteImagesStages(projectInfo)
    }

    deployComponents(projectInfo: projectInfo,
                     componentsToDeploy: projectInfo.componentsToPromote,
                     componentsToRemove: projectInfo.componentsToRemove,
                     imageTag: projectInfo.deployToEnv)
}
