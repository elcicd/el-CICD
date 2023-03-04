/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 */
 
def runComponentDeploymentStages(def projectInfo, def components) {    
    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
    def imageRegistry = el.cicd["${ENV_TO}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def imagePullSecret = "el-cicd-${projectInfo.deployToEnv}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"

    def ingressHostDomain = (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''

    def commonValues = ["profiles='{${projectInfo.deployToEnv}}'",
                        "elCicdDefaults.imagePullSecret=${imagePullSecret}",
                        "elCicdDefaults.ingressHostDomain='${ingressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "elCicdDefs.PROJECT_ID=${projectInfo.id}",
                        "elCicdDefs.RELEASE_VERSION=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
                        "elCicdDefs.BUILD_NUMBER=\${BUILD_NUMBER}",
                        "elCicdDefs.EL_CICD_PROFILES=${projectInfo.deployToEnv}",
                        "elCicdDefs.SDLC_ENV=${projectInfo.deployToEnv}",
                        "elCicdDefs.META_INFO_POSTFIX=${el.cicd.META_INFO_POSTFIX}"]

    sh "helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}"

    def helmStages = concurrentUtils.createParallelStages("Component Deployment/Removal", components) { component ->
        if (component.flaggedForDeployment) {
            def componentImage = "${imageRegistry}/${projectInfo.id}-${component.name}:${projectInfo.deployToEnv}"
            def compValues = ["elCicdDefaults.appName=${component.name}",
                              "elCicdDefaults.image=${componentImage}",
                              "elCicdDefs.COMPONENT_NAME=${component.name}",
                              "elCicdDefs.CODE_BASE=${component.codeBase}",
                              "elCicdDefs.SCM_REPO=${component.scmRepoName}",
                              "elCicdDefs.SRC_COMMIT_HASH=${component.srcCommitHash}",
                              "elCicdDefs.DEPLOYMENT_BRANCH=${component.deploymentBranch ?: el.cicd.UNDEFINED}",
                              "elCicdDefs.DEPLOYMENT_COMMIT_HASH=${component.deploymentCommitHash}"]
            compValues.addAll(commonValues)
            
            helmUpgradeInstall(projectInfo, component, compValues)
        }
        else if (component.flaggedForRemoval) {
            helmUninstall(projectInfo, component)
        }
    }
    
    parallel(helmStages)
}

def helmUpgradeInstall(def projectInfo, def component, def compValues) {
    dir("${component.workDir}/${el.cicd.EL_CICD_CHART_VALUES_DIR}") {
        sh """            
            VALUES_FILES=\$(find . -maxdepth 1 -type f \\( -name *values*.yaml -o -name *values*.yml -o -name *values*.json \\) -printf '-f %f ')

            if [[ -d ./${projectInfo.deployToEnv} ]]
            then
                ENV_FILES=\$(find ./${projectInfo.deployToEnv} -maxdepth 1 -type f \\( -name *.yaml -o -name *.yml -o -name *.json \\) -printf '%f ')
                ENV_FILES=\$(for FILE in \$ENV_FILES; do echo -n "--set-file=elCicdRawYaml.\$(echo \$FILE | sed s/\\\\./_/g )=./${projectInfo.deployToEnv}/\$FILE "; done)
            fi

            HELM_FLAGS=("template --debug" "upgrade --atomic --install --history-max=1")
            for FLAGS in "\${HELM_FLAGS[@]}"
            do
                ${shCmd.echo ''}
                helm \${FLAGS} \
                    --set-string ${compValues.join(' --set-string ')} \
                    \${VALUES_FILES} \${ENV_FILES} \
                    -f ${el.cicd.CONFIG_HELM_DIR}/default-component-values.yaml \
                    -f ${el.cicd.EL_CICD_CHART_VALUES_DIR}/component-meta-info-values.yaml \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    elCicdCharts/elCicdChart
            done
        """
    }
}

def helmUninstall(def projectInfo, def component) {
    sh """
        if [[ ! -z \$(helm list --short --filter ${component.name} -n ${projectInfo.deployToNamespace}) ]]
        then
            helm uninstall --wait ${component.name} -n ${projectInfo.deployToNamespace}
        fi
    """
}

def waitForAllTerminatingPodsToFinish(def projectInfo) {
    loggingUtils.echoBanner("WAIT FOR ANY TERMINATING PODS TO COMPLETE")
    
    def jsonPath = "jsonpath='{.items[?(@.metadata.deletionTimestamp)].metadata.name}'"
    sh """
        TERMINATING_PODS=\$(oc get pods -n ${projectInfo.deployToNamespace} -l projectid=${projectInfo.id} -o=${jsonPath} | tr '\n' ' ')
        if [[ ! -z \${TERMINATING_PODS} ]]
        then
            ${shCmd.echo '', '--> WAIT FOR OLD PODS TO COMPLETE TERMINATION', ''}
            
            oc wait --for=delete pod \${TERMINATING_PODS} -n ${projectInfo.deployToNamespace} --timeout=600s
        
            ${shCmd.echo '', '--> ALL OLD PODS TERMINATED AND REMOVED', ''}
        fi
    """
}