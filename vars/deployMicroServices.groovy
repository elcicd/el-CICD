/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys microservice into enviroment.
 *
 */

def call(Map args) {
    def env = (args.projectInfo.deployToNamespace - args.projectInfo.id).toUpperCase()

    stage('Build templates and retrieve template definitions') {
        if (args.microServices) {
            deploymentUtils.processTemplateDefs(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING BUILD TEMPLATES AND RETRIEVE TEMPLATE DEFINITIONS"
        }
    }

    stage('Remove all microservices to be deployed, if selected') {
        if (args.recreate) {
            deploymentUtils.removeMicroservices(args.projectInfo, args.microServices)
        }
        else if (args.recreateAll) {
            deploymentUtils.removeAllMicroservices(args.projectInfo)
        }
        else {
            echo "RECREATE NOT SELECTED: SKIPPING REMOVE ALL MICROSERVICES TO BE DEPLOYED"
        }
    }

    stage('Process all openshift templates') {
        if (args.microServices) {
            deploymentUtils.processTemplates(args.projectInfo, args.microServices, args.imageTag)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING PROCESS ALL OKD TEMPLATES"
        }
    }

    stage('Apply and deploy all openshift resources') {
        if (args.microServices) {
            deployToEnv.cleanupOldDeployments(args.projectInfo, args.microServices)

            deploymentUtils.applyResources(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING DEPLOY IMAGE IN ${env} FROM ARTIFACT REPOSITORY"
        }
    }

    stage('Confirm successful deployment in namespace from artifact repository') {
        if (args.microServices) {
            deploymentUtils.confirmDeployments(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES WERE DEPLOYED: SKIPPING CONFIRMATION OF DEPLOYMENTS"
        }
    }

    stage('Update microservice meta-info maps') {
        if (args.microServices) {
            deploymentUtils.updateMicroServiceMetaInfo(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES WERE DEPLOYED: SKIPPING UPDATE MICROSERVICE META-INFO MAPS"
        }
    }

    stage('Cleanup orphaned openshift resources') {
        if (args.microServices) {
            deploymentUtils.cleanupOrphanedResources(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES WERE DEPLOYED: SKIPPING CLEANUP ORPHANED OKD RESOURCES"
        }
    }

    stage('Remove microservices selected for removal') {
        if (args.microServicesToRemove) {
            deploymentUtils.removeMicroservices(args.projectInfo, args.microServicesToRemove)
        }
        else {
            echo "NO MICROSERVICES TO REMOVE: SKIPPING REMOVE MICROSERVICES SELECTED FOR REMOVAL"
        }
    }
}