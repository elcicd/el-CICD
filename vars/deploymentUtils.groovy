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
    
    def projectValues =
        ["elCicdChart.projectId=${projectInfo.id}",
         "elCicdChart.releaseVersionTag=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
         "elCicdChart.imageRepository=${imageRepository}",
         "elCicdChart.imageTag=${projectInfo.deployToEnv}",
         "elCicdChart.pullSecret=${pullSecret}",
         "elCicdChart.buildNumber=${BUILD_NUMBER}",
         "'elCicdChart.profiles={${projectInfo.deployToEnv}}'"]
         
    def ingressHostSuffix =
        (projectInfo.deployToEnv != projectInfo.prodEnv) ? (projectInfo.deployToNamespace - projectInfo.id) : ''
    microServices.each { microService ->
        def msValues = ["elCicdChart.microService=${microService.name}",
                        "elCicdChart.ingressHostSuffix='${ingressHostSuffix}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "elCicdChart.gitRepoName=${microService.gitRepoName}",
                        "elCicdChart.srcCommitHash=${microService.srcCommitHash}",
                        "elCicdChart.deploymentBranch=${microService.deploymentBranch ?: el.cicd.UNDEFINED}",
                        "elCicdChart.deploymentCommitHash=${microService.deploymentCommitHash}"]
        msValues.addAll(projectValues)
        
        dir("${microService.workDir}/${el.cicd.MICROSERVICE_DEPLOY_DEF_DIR}") {
            sh """
                rm -f charts/*
                
                REGEX_VALUES_FILES='.*/values.*\\.(yml|yaml)'
                VALUES_FILES=\$(find . -maxdepth 1 -regextype egrep -regex \${REGEX_VALUES_FILES} -printf '-f %f ')
                
                mkdir -p ${el.cicd.DEP_CHART_DIR}
                mkdir -p ${el.cicd.TEMP_CHART_DIR}
                cp -r ${el.cicd.HELM_CHART_DIR} ${el.cicd.DEP_CHART_DIR}
                cp -r . ${el.cicd.TEMP_CHART_DIR}
                
                ln -s ${el.cicd.HELM_CHART_DIR} ../../el-CICD-config/managed-helm-chart
                helm dependency update ${el.cicd.TEMP_CHART_DIR}
                            
                helm template --debug \
                    --set ${msValues.join(' --set ')} \
                    \${VALUES_FILES} \
                    -f ${el.cicd.HELM_CHART_DIR}/values-default.yaml \
                    ${microService.name} ${el.cicd.TEMP_CHART_DIR} \
                    -n ${projectInfo.deployToNamespace}
                
                helm upgrade --install --history-max=1 --cleanup-on-fail \
                    --set ${msValues.join(' --set ')} \
                    \${VALUES_FILES} \
                    -f ${el.cicd.HELM_CHART_DIR}/values-default.yaml \
                    ${microService.name} ${el.cicd.TEMP_CHART_DIR} \
                    -n ${projectInfo.deployToNamespace}
            """
        }
    }
}

def confirmDeployments(def projectInfo, def microServices) {
    assert projectInfo; assert microServices
    
    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')
    loggingUtils.shellEchoBanner("CONFIRM DEPLOYMENT IN ${projectInfo.deployToNamespace} FROM ARTIFACT REPOSITORY:",
                                 "${microServiceNames}")

    sh """
        for MICROSERVICE_NAME in ${microServiceNames}
        do
            DEPLOYS=\$(oc get dc,deploy -l microservice=\${MICROSERVICE_NAME} -o name -n ${projectInfo.deployToNamespace})
            if [[ ! -z \${DEPLOYS} ]]
            then
                echo \${DEPLOYS} | xargs -n1 -t oc rollout status -n ${projectInfo.deployToNamespace}
            fi
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
        COUNTER=1
        until [[ -z \$(oc get pods -n ${deployToNamespace} | grep 'Terminating') ]]
        do
            yes - | head -\${COUNTER} | paste -s -d '' -
            sleep 2
            let "COUNTER++"
        done
        set -x
    """
}
