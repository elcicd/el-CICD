/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def components = args.components
    def componentsToRemove = args.componentsToRemove

    def envCaps = (projectInfo.deployToNamespace - projectInfo.id).toUpperCase()

    stage('Remove selected components, if selected') {
        if (args.recreate) {
            deploymentUtils.removeMicroservices(projectInfo)
        }
        else if (args.recreateAll) {
            deploymentUtils.removeMicroservices(projectInfo, components)
        }
        else {
            echo "RECREATE NOT SELECTED: SKIPPING REMOVE ALL MICROSERVICES TO BE DEPLOYED"
        }
    }

    if (components) {
        deploymentUtils.deployComponents(projectInfo, components)
    }
    else {
        echo "NO MICROSERVICES TO DEPLOY: SKIPPING DEPLOYMENT"
    }

    stage('Confirm successful deployment in namespace from artifact repository') {
        if (components) {
            deploymentUtils.confirmDeployments(projectInfo, components)
        }
        else {
            echo "NO DEPLOYMENTS OF MICROSERVICES TO CONFIRM: SKIPPING DEPLOY IMAGE IN ${envCaps} FROM ARTIFACT REPOSITORY"
        }
    }

    stage('Remove components selected for removal') {
        if (componentsToRemove) {
            deploymentUtils.removeMicroservices(projectInfo, componentsToRemove)
        }
        else {
            echo "NO MICROSERVICES TO REMOVE: SKIPPING REMOVE MICROSERVICES SELECTED FOR REMOVAL"
        }
    }

    if (components.find { it.deploymentBranch}) {
        stage('Inform users of success') {
            def checkoutMsgs = []
            components.each { component ->
                checkoutMsgs += ''
                checkoutMsgs += "**********"
                checkoutMsgs += "DEPLOYMENT BRANCH FOR ${component.name}: ${component.deploymentBranch}"
                checkoutMsgs += "git checkout ${component.deploymentBranch}"
                checkoutMsgs += "**********"
            }

            loggingUtils.echoBanner("DEPLOYMENT COMPLETE.  CURRENT DEPLOYMENT BRANCHES FOR PATCHING IN ${projectInfo.deployToNamespace}:", checkoutMsgs)
        }
    }
}