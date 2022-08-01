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
    def imagePullSecret = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]

    def ingressHostSuffix =
        (projectInfo.deployToEnv != projectInfo.prodEnv) ? (projectInfo.deployToNamespace - projectInfo.id) : ''

    def commonValues = ["projectId=${projectInfo.id}",
                        "releaseVersionTag=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
                        "imageRepository=${imageRepository}",
                        "imageTag=${projectInfo.deployToEnv}",
                        "imagePullSecret=${imagePullSecret}",
                        "ingressHostSuffix='${ingressHostSuffix}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "buildNumber=\${BUILD_NUMBER}",
                        "profiles='{${projectInfo.deployToEnv}}'"]

    def kustomizeSh = libraryResource "${el.cicd.DEFAULT_KUSTOMIZE}/${el.cicd.DEFAULT_KUSTOMIZE}.sh"
    def kustomizationChart = libraryResource "${el.cicd.DEFAULT_KUSTOMIZE}/Chart.yaml"
    def kustomizationTemplate = libraryResource "${el.cicd.DEFAULT_KUSTOMIZE}/templates/kustomization.yaml"

    microServices.each { microService ->
        dir ("${microService.workDir}/${el.cicd.DEFAULT_HELM_DIR}/${el.cicd.DEFAULT_KUSTOMIZE}") {
            writeFile text: kustomizeSh, file: "${el.cicd.DEFAULT_KUSTOMIZE}.sh"
            writeFile text: kustomizationChart, file: "Chart.yaml"
        }

        dir ("${microService.workDir}/${el.cicd.DEFAULT_HELM_DIR}/${el.cicd.DEFAULT_KUSTOMIZE}/templates") {
            writeFile text: kustomizationTemplate, file: "kustomization.yaml"
        }

        dir("${microService.workDir}/${el.cicd.DEFAULT_HELM_DIR}") {
            def msCommonValues = ["microService=${microService.name}",
                                  "gitRepoName=${microService.gitRepoName}",
                                  "srcCommitHash=${microService.srcCommitHash}",
                                  "deploymentBranch=${microService.deploymentBranch ?: el.cicd.UNDEFINED}",
                                  "deploymentCommitHash=${microService.deploymentCommitHash}",
                                  "renderValuesForKust=true"]
            msCommonValues.addAll(commonValues)

            sh """
                rm -rf charts

                mkdir -p ./${el.cicd.DEFAULT_KUSTOMIZE}/resources
                cp -v ${projectInfo.deployToEnv}/* ./${el.cicd.DEFAULT_KUSTOMIZE}/resources

                mkdir -p ./${el.cicd.DEFAULT_KUSTOMIZE}/generators
                mkdir -p ./${el.cicd.DEFAULT_KUSTOMIZE}/transformers
                mkdir -p ./${el.cicd.DEFAULT_KUSTOMIZE}/validators

                helm dependency update .
                
                chmod +x ./${el.cicd.DEFAULT_KUSTOMIZE}/${el.cicd.DEFAULT_KUSTOMIZE}.sh                
                helm upgrade --install --history-max=0 --cleanup-on-fail --debug ${microService.name} . \
                    -f values.yml \
                    -f ${el.cicd.CONFIG_DIR}/${el.cicd.DEFAULT_HELM_DIR}/values-default.yaml \
                    --set elCicdChart.${msCommonValues.join(' --set elCicdChart.')} \
                    --post-renderer ./${el.cicd.DEFAULT_KUSTOMIZE}/${el.cicd.DEFAULT_KUSTOMIZE}.sh \
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
