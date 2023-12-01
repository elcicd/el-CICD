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


    stage ("Clean up failed upgrades/installs") {
        loggingUtils.echoBanner("CLEAN UP ANY PREVIOUSLY FAILED UPGRADES/INSTALLS")
        deploymentUtils.cleanupFailedInstalls(projectInfo)
    }

    stage("Remove component(s)") {
        if (componentsToRemove) {            
            deploymentUtils.removeComponents(projectInfo, componentsToRemove)
        }
        else {
            loggingUtils.echoBanner("NO COMPONENTS TO REMOVE: SKIPPING")
        }
    }

    stage("Setup component directories") {
        if (componentsToDeploy) {
            loggingUtils.echoBanner("SETUP COMPONENT(S) DEPLOYMENT DIRECTORY:", componentsToDeploy.collect { it.name }.join(', '))

            deploymentUtils.setupDeploymentDirs(projectInfo, componentsToDeploy)
        }
        else {
            loggingUtils.echoBanner("NO COMPONENTS TO DEPLOY: SKIPPING")
        }
    }

    stage("Deploy component(s)") {
        if (componentsToDeploy) {
            loggingUtils.echoBanner("DEPLOY COMPONENT(S):", componentsToDeploy.collect { it.name }.join(', '))

            deploymentUtils.runComponentDeploymentStages(projectInfo, componentsToDeploy)
        }
        else {
            loggingUtils.echoBanner("NO COMPONENTS TO DEPLOY: SKIPPING")
        }
    }
    
    stage("Summary") {
        componentsToRemove.each { it.flaggedForRemoval = true }
        componentsToDeploy.each { it.flaggedForDeployment = true }
        
        deploymentUtils.outputDeploymentSummary(projectInfo)
    }
}