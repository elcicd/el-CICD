/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OKD resources
 */

def cleanupFailedInstalls(def projectInfo) {
    sh """
        COMPONENT_NAMES=\$(helm list --uninstalling --failed  -q  -n ${projectInfo.deployToNamespace})
        if [[ ! -z \${COMPONENT_NAMES} ]]
        then
            for COMPONENT_NAME in \${COMPONENT_NAMES}
            do
                helm uninstall -n ${projectInfo.deployToNamespace} \${COMPONENT_NAME} --no-hooks
            done
        fi
    """
}

def runComponentRemovalStages(def projectInfo, def components) {
    def helmStages = concurrentUtils.createParallelStages("Component Removals", components) { component ->
        sh """
            if [[ ! -z \$(helm list --short --filter ${component.name} -n ${projectInfo.deployToNamespace}) ]]
            then
                helm uninstall --wait ${component.name} -n ${projectInfo.deployToNamespace}
            fi
        """
    }

    parallel(helmStages)

    waitForAllTerminatingPodsToFinish(projectInfo)
}

def setupHelmValuesFile(def projectInfo, def componentsToDeploy) {
    def valueFilePattern = '*values.y*ml'
    def valuesDirs = "${valueFilePattern} ${projectInfo.deployToEnv}/${valueFilePattern}"
    valuesDirs += projectInfo.releaseVariant ? " ${projectInfo.releaseVariant} ${projectInfo.deployToEnv}-${projectinfo.releaseVariant}/${valueFilePattern}" : ''
    
    def commonHelmValues = getProjectCommonHelmValues(projectInfo)
    def tmpValuesFile = 'values.yaml.tmp'
    componentsToDeploy.each { component ->
        def compHelmValues = commonHelmValues +
                         ["  objName: ${component.name}",
                          "  image: ${componentImage}",
                          "  COMPONENT_NAME=${component.name}"]
        dir(component.workDir/el.cicd.CHART_DEPLOY_DIR) {
            sh """                
                rm -f ${tmpValuesFile}
                VALUES_FILES=\$(ls --reverse ${valuesDirs} 2>/dev/null)
                for VALUES_FILE in \${VALUES_FILES} elCicdValues.yaml
                do
                    echo "\n# Values File Source: \${VALUES_FILE}" >> ${tmpValuesFile}
                    cat \${VALUES_FILE} >> ${tmpValuesFile}
                done
                echo ${compHelmValues.join('\n')} >> ${tmpValuesFile}
                
                rm -rf \${VALUES_FILES} \$(ls -d */ | grep -v -e charts -e templates -e kustomize | tr '\n' ' ')
                mv values.yaml.tmp values.yaml
        
                ${loggingUtils.shellEchoBanner("Final ${component.name} Helm values.yaml")}
                
                cat values.yaml
            """
        }
    }
}

def getProjectCommonHelmValues(def projectInfo) {
    return ["elCicdProfiles: {${projectInfo.deployToEnv}}",
            "elCicdDefaults:",
            "  imagePullSecret: ${imagePullSecret}",
            "  ingressHostDomain: ${ingressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}",
            "  SDLC_ENV: ${projectInfo.deployToEnv}",
            "  TEAM_ID: ${projectInfo.teamInfo.id}",
            "  PROJECT_ID: ${projectInfo.id}"]
}

def setupKustomizeOverlays(def projectInfo, def componentsToDeploy) {
    componentsToDeploy.each { component ->
        def kustFile = 'kustomization.yaml'
        
        def bases = projectInfo.releaseVariant ? "${projectInfo.deployToEnv}-${projectinfo.releaseVariant} " : ''
        bases += "${projectInfo.deployToEnv} ${el.cicd.BASE_KUSTOMIZE_DIR}"
        
        dir(component.workDir/el.cicd.CHART_DEPLOY_DIR) {
            mkdir -p el.cicd.KUSTOMIZE_DIR
            dir(el.cicd.KUSTOMIZE_DIR) {
                sh """
                    for BASE in \${BASES}
                    do
                        KUST_DIRS="\${KUST_DIRS} -e \${BASE} "
                        mkdir -p \${BASE}
                        if [[ ! -f \${BASE}/${kustFile} ]]
                        then
                            (cd \${BASE} && kustomize create --autodetect)
                        fi
                        
                        if [[ ! -z "\${LAST_BASE}" ]]
                        then
                            (cd \${BASE} && kustomize edit add resource ../\${LAST_BASE})
                        fi
                        LAST_BASE=\${BASE}
                    done
                    
                    rm -rf ${el.cicd.EL_CICD_KUSTOMIZE_DIR} \$(ls -d */ | grep -v \${KUST_DIRS} | tr '\n' ' ')
                    mkdir -p ${el.cicd.EL_CICD_KUSTOMIZE_DIR}
                    helm template --debug \
                                  --set-string "elCicdDefs.RESOURCES={\${BASES}[0]}" \
                                  --set-string "elCicdDefs.COMMON_LABELS={${projectInfo.id}, ${component.name}}" \
                                  -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/common-labels-values.yaml \
                                  @elCicdCharts/elCicdChart  > ${el.cicd.EL_CICD_KUSTOMIZE_DIR}/${kustFile}
                """
            }
        }
    }
}

def setupDeploymentDir(def projectInfo, def componentsToDeploy) {
    componentsToDeploy.each { component ->
        dir(component.deploymentDir) {
            sh """
                cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${el.cicd.COMP_KUST_SH} .
                
                if [[ ! -f Chart.yaml ]]
                then
                    mkdir -p templates
                    cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/Chart.yaml .
                    cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/templates/* templates
                fi
            """
        }
    }
}

def runComponentDeploymentStages(def projectInfo, def components) {
    sh "helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}"

    def helmStages = concurrentUtils.createParallelStages("Deploying", components) { component ->
        dir(component.deploymentDir) {
            sh """
                helm upgrade --install --atomic \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    . \
                    --post-renderer ${el.cicd.COMP_KUST_SH} \
                    --post-renderer-args ${el.cicd.KUSTOMIZE_DIR} \
                                        ${el.cicd.EL_CICD_KUSTOMIZE_DIR} \
                                        ${el.cicd.BASE_KUSTOMIZE_DIR} \
                                        ${el.cicd.PRE_KUST_HELM_FILE} \
                                        ${el.cicd.POST_KUST_HELM_FILE}
                
                helm get manifest ${component.name} -n ${projectInfo.deployToNamespace} 
            """
        }
    }

    parallel(helmStages)

    waitForAllTerminatingPodsToFinish(projectInfo)
}

def waitForAllTerminatingPodsToFinish(def projectInfo) {
    def jsonPath = "jsonpath='{.items[?(@.metadata.deletionTimestamp)].metadata.name}'"
    sh """
        TERMINATING_PODS=\$(oc get pods -n ${projectInfo.deployToNamespace} -l projectid=${projectInfo.id} -o=${jsonPath} | tr '\n' ' ')
        if [[ ! -z \${TERMINATING_PODS} ]]
        then
            ${shCmd.echo '', '--> WAIT FOR PODS TO COMPLETE TERMINATION', ''}

            oc wait --for=delete pod \${TERMINATING_PODS} -n ${projectInfo.deployToNamespace} --timeout=600s

            ${shCmd.echo '', '--> NO TERMINATING PODS REMAINING', ''}
        fi
    """
}

def outputDeploymentSummary(def projectInfo) {
    def resultsMsgs = ["DEPLOYMENT CHANGE SUMMARY FOR ${projectInfo.deployToNamespace}:", '']
    projectInfo.components.each { component ->
        if (component.flaggedForDeployment || component.flaggedForRemoval) {
            resultsMsgs += "**********"
            resultsMsgs += ''
            def checkoutBranch = component.deploymentBranch ?: component.scmBranch
            resultsMsgs += component.flaggedForDeployment ? "${component.name} DEPLOYED FROM GIT:" : "${component.name} REMOVED FROM NAMESPACE"
            if (component.flaggedForDeployment) {
                def refs = component.scmBranch.startsWith(component.srcCommitHash) ?
                    "    Git image source ref: ${component.srcCommitHash}" :
                    "    Git image source refs: ${component.scmBranch} / ${component.srcCommitHash}"

                resultsMsgs += "    Git deployment ref: ${checkoutBranch}"
                resultsMsgs += "    git checkout ${checkoutBranch}"
            }
            resultsMsgs += ''
        }
    }
    resultsMsgs += "**********"

    loggingUtils.echoBanner(resultsMsgs)
}