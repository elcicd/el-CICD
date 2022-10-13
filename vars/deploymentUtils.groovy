/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def deployMicroservices(def projectInfo, def components) {
    assert projectInfo; assert components

    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
    def imageRepository = el.cicd["${ENV_TO}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def imagePullSecret = el.cicd["${ENV_TO}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"]

    def ingressHostSuffix =
        (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''

    def commonValues = ["projectId=${projectInfo.id}",
                        "elCicdDefs.PROJECT_ID=${projectInfo.id}",
                        "releaseVersionTag=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
                        "global.defaultImagePullSecret=${imagePullSecret}",
                        "ingressHostSuffix='${ingressHostSuffix}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "buildNumber=\${BUILD_NUMBER}",
                        "profiles='{${projectInfo.deployToEnv}}'",
                        "renderValuesForKust=true"]

    def kustomizeSh = libraryResource "${el.cicd.DEFAULT_KUSTOMIZE}/${el.cicd.DEFAULT_KUSTOMIZE}.sh"
    def kustomizationChart = libraryResource "${el.cicd.DEFAULT_KUSTOMIZE}/Chart.yaml"
    def kustomizationTemplate = libraryResource "${el.cicd.DEFAULT_KUSTOMIZE}/templates/kustomization.yaml"

    components.each { component ->
        dir ("${component.workDir}/${el.cicd.DEFAULT_HELM_DIR}/${el.cicd.DEFAULT_KUSTOMIZE}") {
            writeFile text: kustomizeSh, file: "${el.cicd.DEFAULT_KUSTOMIZE}.sh"
            writeFile text: kustomizationChart, file: "Chart.yaml"
        }

        dir ("${component.workDir}/${el.cicd.DEFAULT_HELM_DIR}/${el.cicd.DEFAULT_KUSTOMIZE}/templates") {
            writeFile text: kustomizationTemplate, file: "kustomization.yaml"
        }

        dir("${component.workDir}/${el.cicd.DEFAULT_HELM_DIR}") {
            def componentImage = "${imageRepository}/${projectInfo.id}-${component.name}:${projectInfo.deployToEnv}"
            def msCommonValues = ["appName=${component.name}",
                                  "component=${component.name}",
                                  "elCicdDefs.MICROSERVICE_NAME=${component.name}",
                                  "global.defaultImage=${componentImage}",
                                  "scmRepoName=${component.scmRepoName}",
                                  "srcCommitHash=${component.srcCommitHash}",
                                  "deploymentBranch=${component.deploymentBranch ?: el.cicd.UNDEFINED}",
                                  "deploymentCommitHash=${component.deploymentCommitHash}"]
            msCommonValues.addAll(commonValues)

            sh """
                rm -rf charts

                for KUST_DIR in resources generators transformers validators
                do
                    mkdir -p ./${el.cicd.DEFAULT_KUSTOMIZE}/\${KUST_DIR}
                    cp -v ${el.cicd.EL_CICD_HELM_DIR}/\${KUST_DIR}/* ./${el.cicd.DEFAULT_KUSTOMIZE}/\${KUST_DIR} 2>/dev/null || :
                done
                cp -v ${projectInfo.deployToEnv}/* ./${el.cicd.DEFAULT_KUSTOMIZE}/resources 2>/dev/null || :

                helm dependency update .

                chmod +x ./${el.cicd.DEFAULT_KUSTOMIZE}/${el.cicd.DEFAULT_KUSTOMIZE}.sh

                VALUES_FILE=\$(if [[ -f values.yml ]]; then echo values.yml; else echo values.yaml; fi)

                SECRET_NAME=\$(oc get secret --ignore-not-found --no-headers -l name=${component.name},status!=deployed \
                              -o custom-columns=:.metadata.name \
                              -n ${projectInfo.deployToNamespace})
                if [[ ! -z \${SECRET_NAME} ]]
                then
                    oc patch secret \${SECRET_NAME} \
                        -p '{"metadata":{"labels":{"status":"deployed"}}}' \
                        --type=merge \
                        -n ${projectInfo.deployToNamespace}
                fi
                
                set +e
                if helm upgrade --force --install --history-max=1 --cleanup-on-fail --debug ${component.name} . \
                    -f \${VALUES_FILE} \
                    -f ${el.cicd.CONFIG_DIR}/${el.cicd.DEFAULT_HELM_DIR}/values-default.yaml \
                    --set-string elCicdChart.${msCommonValues.join(' --set-string elCicdChart.')} \
                    --post-renderer ./${el.cicd.DEFAULT_KUSTOMIZE}/${el.cicd.DEFAULT_KUSTOMIZE}.sh \
                    -n ${projectInfo.deployToNamespace}
                then
                    ${shCmd.echo '', 'Helm UPGRADE/INSTALL COMPLETE', ''}
                else
                    set +x
                    echo 
                    echo 'HELM ERROR'
                    echo 'Attempting to generate template output...'
                    echo
                    helm template --debug ${component.name} . \
                        -f \${VALUES_FILE} \
                        -f ${el.cicd.CONFIG_DIR}/${el.cicd.DEFAULT_HELM_DIR}/values-default.yaml \
                        --set-string elCicdChart.${msCommonValues.join(' --set-string elCicdChart.')} \
                        -n ${projectInfo.deployToNamespace}
                    set -ex
                    exit 1
                fi
                set -e
            """
        }
    }
}

def confirmDeployments(def projectInfo, def components) {
    assert projectInfo; assert components

    def componentNames = components.collect { component -> component.name }.join(' ')
    loggingUtils.shellEchoBanner("CONFIRM DEPLOYMENT IN ${projectInfo.deployToNamespace} FROM ARTIFACT REPOSITORY:",
                                 "${componentNames}")

    sh """
        for MICROSERVICE_NAME in ${componentNames}
        do
            DEPLOYS=\$(oc get deploy -l component=\${MICROSERVICE_NAME} -o name -n ${projectInfo.deployToNamespace})
            if [[ ! -z \${DEPLOYS} ]]
            then
                echo \${DEPLOYS} | xargs -n1 -t oc rollout status -n ${projectInfo.deployToNamespace}
            fi
        done
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)
}

def removeMicroservices(def projectInfo) {
    removeMicroservices(projectInfo, projectInfo.components)
}

def removeMicroservices(def projectInfo, def components) {
    assert projectInfo; assert components

    def componentNames = components.collect { component -> component.name }.join(' ')

    loggingUtils.echoBanner("REMOVING SELECTED MICROSERVICES AND ALL ASSOCIATED RESOURCES FROM ${projectInfo.deployToNamespace}:", "${componentNames}")

    sh """
        for MICROSERVICE_NAME in ${componentNames}
        do
            helm uninstall \${MICROSERVICE_NAME} -n ${projectInfo.deployToNamespace}
        done
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)
}

def waitingForPodsToTerminate(def deployToNamespace) {
    sh """
        ${shCmd.echo '', 'Confirming component pods have finished terminating...'}
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
