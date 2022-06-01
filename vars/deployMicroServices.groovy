/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys microservice into enviroment.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def microServices = args.microServices
    def microServicesToRemove = args.microServicesToRemove

    def envCaps = (projectInfo.deployToNamespace - projectInfo.id).toUpperCase()

    stage('Build templates and retrieve template definitions') {
        if (microServices) {
            deploymentUtils.processTemplateDefs(projectInfo, microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING BUILD TEMPLATES AND RETRIEVE TEMPLATE DEFINITIONS"
        }
    }

    stage('Remove all microservices to be deployed, if selected') {
        if (args.recreate) {
            deploymentUtils.removeMicroservices(projectInfo, microServices)
        }
        else if (args.recreateAll) {
            deploymentUtils.removeAllMicroservices(projectInfo)
        }
        else {
            echo "RECREATE NOT SELECTED: SKIPPING REMOVE ALL MICROSERVICES TO BE DEPLOYED"
        }
    }

    stage('Process all openshift templates') {
        if (microServices) {
            deploymentUtils.processTemplates(projectInfo, microServices, args.imageTag)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING PROCESS ALL OKD TEMPLATES"
        }
    }

    stage('Apply all openshift resources') {
        if (microServices) {
            deploymentUtils.applyResources(projectInfo, microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING APPLY ALL OKD RESOURCES"
        }
    }

    stage('Deploy image in namespace from artifact repository') {
        if (microServices) {
            deploymentUtils.rolloutLatest(projectInfo, microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING DEPLOY IMAGE IN ${envCaps} FROM ARTIFACT REPOSITORY"
        }
    }

    stage('Update microservice meta-info maps') {
        if (microServices) {
            deploymentUtils.updateMicroServiceMetaInfo(projectInfo, microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING UPDATE MICROSERVICE META-INFO MAPS"
        }
    }

    stage('Cleanup orphaned openshift resources') {
        if (microServices) {
            deploymentUtils.cleanupOrphanedResources(projectInfo, microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING CLEANUP ORPHANED OKD RESOURCES"
        }
    }

    stage('Confirm successful deployment in namespace from artifact repository') {
        if (microServices) {
            deploymentUtils.confirmDeployments(projectInfo, microServices)
        }
        else {
            echo "NO DEPLOYMENTS OF MICROSERVICES TO CONFIRM: SKIPPING DEPLOY IMAGE IN ${envCaps} FROM ARTIFACT REPOSITORY"
        }
    }

    stage('Remove microservices selected for removal') {
        if (microServicesToRemove) {
            deploymentUtils.removeMicroservices(projectInfo, microServicesToRemove)
        }
        else {
            echo "NO MICROSERVICES TO REMOVE: SKIPPING REMOVE MICROSERVICES SELECTED FOR REMOVAL"
        }
    }

    if (microServices.find { it.deploymentBranch}) {
        stage('Inform users of success') {
            def checkoutMsgs = []
            microServices.each { microService ->
                checkoutMsgs += ''
                checkoutMsgs += "**********"
                checkoutMsgs += "DEPLOYMENT BRANCH FOR ${microService.name}: ${microService.deploymentBranch}"
                checkoutMsgs += "git checkout ${microService.deploymentBranch}"
                checkoutMsgs += "**********"
            }

            loggingUtils.echoBanner("DEPLOYMENT COMPLETE.  CURRENT DEPLOYMENT BRANCHES FOR PATCHING IN ${projectInfo.deployToNamespace}:", checkoutMsgs)
        }
    }
}