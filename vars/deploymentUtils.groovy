/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def deployComponents(def projectInfo, def components) {
    assert projectInfo; assert components

    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
    def imageRegistry = el.cicd["${ENV_TO}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def imagePullSecret = "el-cicd-${projectInfo.deployToEnv}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"

    def ingressHostDomain = (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''

    def commonValues = ["elCicdDefs.PROJECT_ID=${projectInfo.id}",
                        "elCicdDefs.RELEASE_VERSION=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
                        "elCicdDefs.BUILD_NUMBER=\${BUILD_NUMBER}",
                        "elCicdDefaults.imagePullSecret=${imagePullSecret}",
                        "elCicdDefaults.ingressHostDomain='${ingressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "profiles='{${projectInfo.deployToEnv}}'",
                        "elCicdDefs.EL_CICD_PROFILES=${projectInfo.deployToEnv}",
                        "elCicdDefs.SDLC_ENV=${projectInfo.deployToEnv}",
                        "elCicdDefs.META_INFO_POSTFIX=${el.cicd.META_INFO_POSTFIX}"]

    def deploymentStages = [failFast: true]
    components.each { component ->
        deploymentStages[component.name] = {
            stage("Deploying ${component.name}") {
                def componentImage = "${imageRegistry}/${projectInfo.id}-${component.name}:${projectInfo.deployToEnv}"
                def compValues = ["elCicdDefaults.appName=${component.name}",
                                    "elCicdDefs.COMPONENT_NAME=${component.name}",
                                    "elCicdDefs.SCM_REPO=${component.scmRepoName}",
                                    "elCicdDefs.SRC_COMMIT_HASH=${component.srcCommitHash}",
                                    "elCicdDefs.DEPLOYMENT_BRANCH=${component.deploymentBranch ?: el.cicd.UNDEFINED}",
                                    "elCicdDefs.DEPLOYMENT_COMMIT_HASH=${component.deploymentCommitHash}",
                                    "elCicdDefaults.image=${componentImage}"]
                compValues.addAll(commonValues)

                dir("${component.workDir}/${el.cicd.DEFAULT_HELM_DIR}") {
                    sh """
                        VALUES_FILES=\$(find . -maxdepth 1 -type f \\( -name *values*.yaml -o -name *values*.yml -o -name *values*.json \\) -printf '-f %f ')

                        if [[ -d ./${projectInfo.deployToEnv} ]]
                        then
                            ENV_FILES=\$(find ./${projectInfo.deployToEnv} -maxdepth 1 -type f \\( -name *.yaml -o -name *.yml -o -name *.json \\) -printf '%f ')
                            ENV_FILES=\$(for FILE in \$ENV_FILES; do echo -n "--set-file=elCicdRawYaml.\$(echo \$FILE | sed s/\\\\./_/g )=./${projectInfo.deployToEnv}/\$FILE "; done)
                        fi

                        ${shCmd.echo ''}
                        helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}

                        ${shCmd.echo ''}
                        helm template --debug \
                            --set-string ${compValues.join(' --set-string ')} \
                            \${VALUES_FILES} \${ENV_FILES} \
                            -f ${el.cicd.CONFIG_HELM_DIR}/default-component-values.yaml \
                            -f ${el.cicd.EL_CICD_HELM_DIR}/component-meta-info-values.yaml \
                            -n ${projectInfo.deployToNamespace} \
                            ${component.name} \
                            elCicdCharts/elCicdChart
                        
                        ${shCmd.echo ''}
                        helm upgrade --atomic --install --history-max=1 \
                            --set-string ${compValues.join(' --set-string ')} \
                            \${VALUES_FILES} \${ENV_FILES} \
                            -f ${el.cicd.CONFIG_HELM_DIR}/default-component-values.yaml \
                            -f ${el.cicd.EL_CICD_HELM_DIR}/component-meta-info-values.yaml \
                            -n ${projectInfo.deployToNamespace} \
                            ${component.name} \
                            elCicdCharts/elCicdChart
                        
                        ${shCmd.echo '', 'Helm UPGRADE/INSTALL COMPLETE', ''}
                    """
                }
            }
        }
    }
    
    parallel(deploymentStages)
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
