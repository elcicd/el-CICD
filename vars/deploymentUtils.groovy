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
    def imageRegistry = el.cicd["${ENV_TO}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def imagePullSecret = "el-cicd-${ENV_TO}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"

    def defaultIngressHostDomain =
        (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''
    
    def commonValues = ["elCicdDefs.PROJECT_ID=${projectInfo.id}",
                        "elCicdDefs.RELEASE_VERSION=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
                        "elCicdDefs.BUILD_NUMBER=\${BUILD_NUMBER}",
                        "elCicdDefaults.imagePullSecret=${imagePullSecret}",
                        "elCicdDefaults.ingressHostDomain='${defaultIngressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "profiles='{${projectInfo.deployToEnv}}'",
                        "elCicdDefs.EL_CICD_PROFILES=${projectInfo.deployToEnv}",
                        "elCicdDefs.SDLC_ENV=${projectInfo.deployToEnv}",
                        "elCicdDefs.META_INFO_POSTFIX=${el.cicd.META_INFO_POSTFIX}"]

    components.each { component ->
        def componentImage = "${imageRegistry}/${projectInfo.id}-${component.name}:${projectInfo.deployToEnv}"
        def msCommonValues = ["elCicdDefaults.appName=${component.name}",
                              "elCicdDefs.COMPONENT_NAME=${component.name}",
                              "elCicdDefs.SCM_REPO=${component.scmRepoName}",
                              "elCicdDefs.SRC_COMMIT_HASH=${component.srcCommitHash}",
                              "elCicdDefs.DEPLOYMENT_BRANCH=${component.deploymentBranch ?: el.cicd.UNDEFINED}",
                              "elCicdDefs.DEPLOYMENT_COMMIT_HASH=${component.deploymentCommitHash}",
                              "elCicdDefaults.image=${componentImage}"]
        msCommonValues.addAll(commonValues)
        
        dir("${component.workDir}/${el.cicd.DEFAULT_HELM_DIR}") {
            sh """
                VALUES_FILES=\$(find . -maxdepth 1 -name *values*.yaml -o -name *values*.yml -o *values*.json -exec echo '-f {} ' \\;)
                
                ENV_FILES=\$(find ./${projectInfo.deployToEnv} -maxdepth 1 -name *.yaml -o -name *.yml -o *.json -exec echo '--set-file elCicdRawYaml.{}={} ' \\;)
                
                ${shCmd.echo ''}
                helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}
                
                set +e
                if helm upgrade --atomic --install --history-max=1 \
                    --set-string ${msCommonValues.join(' --set-string ')} \
                    \${VALUES_FILES} \
                    -f ${el.cicd.CONFIG_HELM_DIR}/default-component-values.yaml \
                    -f ${el.cicd.EL_CICD_HELM_DIR}/component-meta-info-values.yaml \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    elCicdCharts/elCicdChart                    
                then
                    ${shCmd.echo '', 'Helm UPGRADE/INSTALL COMPLETE', ''}
                else
                    set +x
                    echo 
                    echo 'HELM ERROR'
                    echo 'Attempting to generate template output...'
                    echo
                    helm template --debug ${component.name} \
                        -f \${VALUES_FILE} \
                        --set-string elCicdChart.${msCommonValues.join(' --set-string elCicdChart.')} \
                        -f ${el.cicd.CONFIG_HELM_DIR}/default-component-values.yaml \
                        -f ${el.cicd.EL_CICD_HELM_DIR}/component-meta-info-values.yaml \
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
        for COMPONENT_NAME in ${componentNames}
        do
            DEPLOYS=\$(oc get deploy -l component=\${COMPONENT_NAME} -o name -n ${projectInfo.deployToNamespace})
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
        for COMPONENT_NAME in ${componentNames}
        do
            helm uninstall \${COMPONENT_NAME} -n ${projectInfo.deployToNamespace}
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
