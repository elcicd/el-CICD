/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys microservice into enviroment.
 *
 */

def call(Map args) {

    stage('Remove all microservices to be deployed for complete recreation') {
        if (args.recreate) {
            deploymentUtils.removeMicroservices(args.projectInfo, args.microServices)
        }
        else if (args.recreateAll) {
            deploymentUtils.removeAllMicroservices(args.projectInfo)
        }
        else {
            echo "RECREATION NOT SELECTED: SKIPPING REMOVE ALL MICROSERVICES TO BE DEPLOYED FOR COMPLETE RECREATION"
        }
    }

    stage('Build templates and retrieve template definitions') {
        if (args.microServices) {
            deploymentUtils.buildTemplatesAndGetParams(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING BUILD TEMPLATES AND RETRIEVE TEMPLATE DEFINITIONS"
        }
    }

    stage('Process all openshift templates') {
        if (args.microServices) {
            deploymentUtils.processTemplates(args.projectInfo, args.microServices, args.imageTag)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING PROCESS ALL OPENSHIFT TEMPLATES"
        }
    }

    stage('Apply all openshift resources') {
        if (args.microServices) {
            deploymentUtils.applyResources(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING APPLY ALL OPENSHIFT RESOURCES"
        }
    }

    stage('Deploy image in namespace from artifact repository') {
        if (args.microServices) {
            deploymentUtils.rolloutLatest(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING DEPLOY IMAGE IN DEV FROM ARTIFACT REPOSITORY"
        }
    }

    stage('Update microservice meta-info maps') {
        if (args.microServices) {
            deploymentUtils.updateMicroServiceMetaInfo(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING UPDATE MICROSERVICE META-INFO MAPS"
        }
    }

    stage('Cleanup orphaned openshift resources') {
        if (args.microServices) {
            deploymentUtils.cleanupOrphanedResources(args.projectInfo, args.microServices)
        }
        else {
            echo "NO MICROSERVICES TO DEPLOY: SKIPPING CLEANUP ORPHANED OPENSHIFT RESOURCES"
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