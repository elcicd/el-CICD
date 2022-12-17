/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */
 
def createComponentDeployStages(def projectInfo, def components) {
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

                runDeploymentShell(projectInfo, component, compValues)
            }
        }
    }
    
    return deploymentStages
}

def runDeploymentShell(def projectInfo, def component, def compValues) {
    dir("${component.workDir}/${el.cicd.DEFAULT_HELM_DIR}") {
        sh """            
            VALUES_FILES=\$(find . -maxdepth 1 -type f \\( -name *values*.yaml -o -name *values*.yml -o -name *values*.json \\) -printf '-f %f ')

            if [[ -d ./${projectInfo.deployToEnv} ]]
            then
                ENV_FILES=\$(find ./${projectInfo.deployToEnv} -maxdepth 1 -type f \\( -name *.yaml -o -name *.yml -o -name *.json \\) -printf '%f ')
                ENV_FILES=\$(for FILE in \$ENV_FILES; do echo -n "--set-file=elCicdRawYaml.\$(echo \$FILE | sed s/\\\\./_/g )=./${projectInfo.deployToEnv}/\$FILE "; done)
            fi
            
            
            helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}

            ${shCmd.echo ''}
            HELM_FLAGS=("template --debug" "upgrade --atomic --install --history-max=1")
            for FLAGS in "\${HELM_FLAGS[@]}"
            do
                ${shCmd.echo ''}
                helm \${FLAGS} \
                    --set-string ${compValues.join(' --set-string ')} \
                    \${VALUES_FILES} \${ENV_FILES} \
                    -f ${el.cicd.CONFIG_HELM_DIR}/default-component-values.yaml \
                    -f ${el.cicd.EL_CICD_HELM_DIR}/component-meta-info-values.yaml \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    elCicdCharts/elCicdChart
            done
            
            CURRENT_PODS=\$(oc get pods -l component=${component.name} \
                                        --field-selector='status.phase=Terminating' \
                                        -o name -n ${projectInfo.deployToNamespace} | \
                                        tr '\n' ' ')
            if [[ ! -z \${CURRENT_PODS} ]]
            then 
                oc wait --for=delete \${CURRENT_PODS} -n ${projectInfo.deployToNamespace} --timeout=600s
            fi
            
            ${shCmd.echo '', 'Helm UPGRADE/INSTALL COMPLETE', ''}
        """
    }
}

def createComponentRemovalStages(def projectInfo, def components) {
    assert projectInfo; assert components
    
    def removalStages = [failFast: true]
    components.each { component ->
        removalStages[component.name] = {
            stage("Uninstalling ${component.name}") {
                sh """
                    if [[ ! -z \$(helm list -q -n ${projectInfo.deployToNamespace} | grep ${component.name}) ]]
                    then
                        helm uninstall --wait ${component.name} -n ${projectInfo.deployToNamespace}
                        oc wait --for=delete pods -l component=${component.name} -n ${projectInfo.deployToNamespace} --timeout=600s
                    fi
                """
            }
        }
    }
    
    return removalStages
}