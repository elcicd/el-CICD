/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def deployMicroservices(def projectInfo, def microServices) {
    assert projectInfo; assert microServices
    
    loggingUtils.echoBanner("GENERATING DEPLOYMENT MANIFESTS FROM MANAGED HELM CHART PROFILES:",
                            "${projectInfo.deployToEnv}")
    
    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
    def imageRepository = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def pullSecret = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]
    
    microServices.each { microService ->
        dir("${microService.workDir}/${el.cicd.MICROSERVICE_DEPLOY_DEF_DIR}") {
            sh """
                mkdir -p ${el.cicd.TEMP_CHART_DIR}
                cp -r ${el.cicd.HELM_CHART_DIR}/* ${el.cicd.TEMP_CHART_DIR}
                cp -n \$(find ./${projectInfo.deployToEnv} -maxdepth 1 -name "*.tpl" -o -name "*.yaml" -o -name "*.yml" -o -name "*.json") \
                    ${el.cicd.TEMP_CHART_TEMPLATES_DIR}
                
                REGEX_VALUES_FILES='.*/values(.*-${projectInfo.deployToEnv}-?)?\\.(yml|yaml)'
                VALUES_FILES=\$(find . -maxdepth 1 -regextype egrep -regex \${REGEX_VALUES_FILES} -printf '-f %f ')
            
                helm upgrade --install --debug \
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
                    -f \${VALUES_FILES} \
                    ${microService.name} ${el.cicd.HELM_CHART_DIR}
                    -n ${projectInfo.deployToNamespace}
            """
        }
    }
}

def confirmDeployments(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')
    sh """
        ${pipelineUtils.shellEchoBanner("CONFIRM DEPLOYMENT IN ${projectInfo.deployToNamespace} FROM ARTIFACT REPOSITORY:", "${microServiceNames}")}
        for MICROSERVICE_NAME in ${microServiceNames}
        do
            oc get dc,deploy -l microservice=\${MICROSERVICE_NAME} -o name | \
                xargs -n1 -t oc rollout status -n ${projectInfo.deployToNamespace}
        done
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)
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
