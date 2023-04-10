/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def componentsToDeploy = args.componentsToDeploy ?: []
    def componentsToRemove = args.componentsToRemove ?: []

    if (!componentsToDeploy && !componentsToRemove) {
        loggingUtils.errorBanner("NO COMPONENTS TO DEPLOY OR REMOVE")
    }

    componentsToDeploy.each { it.flaggedForDeployment = true; it.flaggedForRemoval = false }
    componentsToRemove.each { it.flaggedForDeployment = false; it.flaggedForRemoval = true }

    stage ("Clean up failed upgrades/installs") {
        loggingUtils.echoBanner("CLEAN UP ANY PREVIOUSLY FAILED UPGRADES/INSTALLS")
        deploymentUtils.cleanupFailedInstalls(projectInfo)
    }

    stage("Remove component(s)") {
        if (componentsToRemove) {            
            runComponentRemovalStages(projectInfo, componentsToRemove)
        }
        else {
            loggingUtils.echoBanner("NO COMPONENTS TO REMOVE: SKIPPING")
        }
    }

    stage("Deploy component(s)") {
        if (componentsToRemove) {
            loggingUtils.echoBanner("REMOVE COMPONENT(S):", componentsToRemove.collect { it.name }.join(', '))
            
            deploymentUtils.

            deploymentUtils.runComponentDeploymentStages(projectInfo, componentsToRemove + componentsToDeploy)
        }
    }
    
    stage("Summary") {
        deploymentUtils.outputDeploymentSummary(projectInfo)
    }
}