/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def deployMicroservices(def projectInfo, def microServices, def valuesFiles, def profiles) {
    assert projectInfo; assert microServices
    
    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
    def imageRepository = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def pullSecret = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]

    microServices.each { microService ->
        dir("${microService.workDir}/${el.cicd.MICROSERVICE_DEPLOY_DEF_DIR}") {
            sh """
                cp ${el.cicd.HELM_CHART_DEFAULTS_DIR}/templates/* ./managed-helm-chart/templates/
            
                helm upgrade --install --atomic \
                    --set projectId=${projectInfo.id} \
                    --set microService=${microService.name} \
                    --set gitRepoName=${microService.gitRepo} \
                    --set srcCommitHash=${microService.srcCommitHash} \
                    --set deploymentBranch=${microService.deploymentBranch ?: el.cicd.UNDEFINED} \
                    --set deploymentCommitHash=${microService.deploymentCommitHash} \
                    --set releaseVersionTag=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED} \
                    --set releaseRegion=${projectInfo.releaseRegion ?: el.cicd.UNDEFINED} \
                    --set imageRepository=${imageRepository} \
                    --set imageTag=${projectInfo.deployToEnv} \
                    --set pullSecret=${pullSecret} \
                    --set buildNumber=${BUILD_NUMBER} \
                    --set profiles=${projectInfo.deployToEnv} \
                    -n ${projectInfo.deployToNamespace} \
                    -f ${valuesFilePaths.join(' -f ')} \
                    ${microService.name} ./managed-helm-chart
            """
    }
}

def removeMicroservices(def projectInfo) {
    removeMicroservices(projectInfo, projectInfo.microServices)
}

def removeMicroservices(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')

    loggingUtils.echoBanner("REMOVING SELECTED MICROSERVICES AND ALL ASSOCIATED RESOURCES FROM ${projectInfo.deployToNamespace}:", "${microServiceNames}")
    
    sh """
        for MICROSERVICE_NAME in ${microServiceNames}
        do
            helm uninstall \${MICROSERVICE_NAME} -n ${projectInfo.deployToNamespace}
        done
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)
}

def waitingForPodsToTerminate(def deployToNamespace) {
    sh """
        ${shCmd.echo '', 'Confirming microservice pods have finished terminating...'}
        set +x
        sleep 2
        COUNTER=1
        while [[ ! -z \$(oc get pods -n ${deployToNamespace} | grep 'Terminating') ]]
        do
            printf -- '-%.0s' {1..\${COUNTER}}
            echo
            sleep 2
            let COUNTER+=1
        done
        set -x
    """
}
