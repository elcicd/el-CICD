/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def cleanupFailedInstalls(def projectInfo) {
    sh """
        COMPONENT_NAMES=\$(helm list --uninstalling --failed  -q  -n ${projectInfo.deployToNamespace})
        if [[ "\${COMPONENT_NAMES}" ]]
        then
            for COMPONENT_NAME in \${COMPONENT_NAMES}
            do
                helm uninstall -n ${projectInfo.deployToNamespace} \${COMPONENT_NAME} --no-hooks
            done
        fi
    """
}

def removeComponents(def projectInfo, def modules) {
    def moduleNames = modules.collect { it. name }.join('|')
    sh """
        RELEASES=\$(helm list --short --filter '${moduleNames}' -n ${projectInfo.deployToNamespace} | tr '\\n' ' ')
        if [[ "\${RELEASES}" ]]
        then
            helm uninstall \${RELEASES} --wait -n ${projectInfo.deployToNamespace}
        else
            ${shCmd.echo 'NOTHING TO CLEAN FROM NAMESPACE; SKIPPING...'}
        fi
    """

    sleep 3
}

def setupDeploymentDirs(def projectInfo, def componentsToDeploy) {
    def commonConfigValues = getProjectCommonHelmValues(projectInfo)
    def imageRegistry = el.cicd["${projectInfo.deployToEnv.toUpperCase()}${el.cicd.OCI_REGISTRY_POSTFIX}"]
    def componentConfigFile = 'elCicdValues.yaml'
    def tmpValuesFile = 'values.yaml.tmp'
    def defaultChartValuesYaml = projectInfo.releaseVersion ? 'helm-subchart-yaml-values.yaml' : 'helm-chart-yaml-values.yaml'

    componentsToDeploy.each { component ->
        dir(component.deploymentDir) {
            def elCicdOverlayDir = "${el.cicd.KUSTOMIZE_DIR}/${el.cicd.EL_CICD_OVERLAY_DIR}"
            dir(elCicdOverlayDir) {
                def compConfigValues = getComponentConfigValues(projectInfo, component, imageRegistry, commonConfigValues)
                writeYaml(file: componentConfigFile, data: compConfigValues)
            }

            sh """
                rm -f ${tmpValuesFile}
                DIR_ARRAY=(.  ${projectInfo.elCicdProfiles.join(' ')})

                set +e
                VALUES_FILES=\$(find \${DIR_ARRAY[@]} -maxdepth 1 -type f \
                                '(' -name '*values*.yaml' -o -name '*values*.yml' ')' \
                                -exec echo -n ' {}' \\; 2>/dev/null)
                set -e

                helm template \${VALUES_FILES/ / -f } -f ${elCicdOverlayDir}/${componentConfigFile} \
                     --set outputMergedValuesYaml=true \
                     render-values-yaml ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart | sed -E '/^#|^---/d' > ${tmpValuesFile}

                ${loggingUtils.shellEchoBanner("Merged ${component.name} Helm values.yaml")}

                cat  ${tmpValuesFile}

                rm -f \${VALUES_FILES}
                mv ${tmpValuesFile} values.yaml

                helm template -f ${elCicdOverlayDir}/${componentConfigFile} \
                              -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/kust-chart-values.yaml \
                              ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart | sed -E '/^#|^---/d' > ${elCicdOverlayDir}/kustomization.yaml

                UPDATE_DEPENDENCIES='update-dependencies'
                if [[ ! -f Chart.yaml ]]
                then
                    helm template --set-string elCicdDefs.VERSION=${projectInfo.releaseVersion ?: '0.1.0'} \
                                  --set-string elCicdDefs.HELM_REPOSITORY_URL=${el.cicd.EL_CICD_HELM_OCI_REGISTRY} \
                                  -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${defaultChartValuesYaml} \
                                  ${component.name} ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart | sed -E '/^#|^---/d' > Chart.yaml

                    ${shCmd.echo('', "--> No Chart.yaml found for ${component.name}; generating default Chart.yaml elcicd-chart:")}

                    cat Chart.yaml

                    ${shCmd.echo('')}
                    if [[ '${projectInfo.deployToEnv}' == '${projectInfo.prodEnv}' ]]
                    then
                        unset UPDATE_DEPENDENCIES
                    fi
                fi

                if [[ "\${UPDATE_DEPENDENCIES}" ]]
                then
                    ${shCmd.echo('', "--> ${component.name} is using a custom Helm chart and/or is being prepared for a non-prod deployment:")}
                    helm dependency update
                    ${shCmd.echo('')}
                fi

                cp -R ${el.cicd.EL_CICD_CHARTS_TEMPLATE_DIR}  ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/.helmignore .

                if [[ '${projectInfo.deployToEnv}' != '${projectInfo.prodEnv}' ]]
                then
                    ${shCmd.echo('', "--> Deploying ${component.name} to ${projectInfo.deployToEnv}; use post-renderer:")}

                    cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${el.cicd.EL_CICD_POST_RENDER_KUSTOMIZE} .
                    chmod +x ${el.cicd.EL_CICD_POST_RENDER_KUSTOMIZE}

                    ${shCmd.echo('')}
                else
                    ${shCmd.echo('',
                                 "--> ${component.name} is a subchart of ${projectInfo.id} and deploying to ${projectInfo.deployToEnv}:",
                                 '    NO POST-RENDERER REQUIRED',
                                 ''
                    )}
                fi
            """
        }
    }
}

def getProjectCommonHelmValues(def projectInfo) {
    projectInfo.elCicdProfiles = projectInfo.deploymentVariant ?
        [projectInfo.deployToEnv, projectInfo.deploymentVariant, "${projectInfo.deployToEnv}-${projectInfo.deploymentVariant}"] :
        [projectInfo.deployToEnv]

    def elCicdDefs = [
        EL_CICD_PROFILES: projectInfo.elCicdProfiles.join(','),
        TEAM_ID: projectInfo.teamInfo.id,
        PROJECT_ID: projectInfo.id,
        RELEASE_VERSION: projectInfo.releaseVersion ?: el.cicd.UNDEFINED,
        BUILD_NUMBER: "${currentBuild.number}",
        SDLC_ENV: projectInfo.deployToEnv,
        META_INFO_POSTFIX: el.cicd.META_INFO_POSTFIX
    ]

    def ingressHostDomain = (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''
    def imagePullSecret = "elcicd-${projectInfo.deployToEnv}${el.cicd.OCI_REGISTRY_CREDENTIALS_POSTFIX}"
    def elCicdDefaults = [
        imagePullSecret: "elcicd-${projectInfo.deployToEnv}${el.cicd.OCI_REGISTRY_CREDENTIALS_POSTFIX}",
        ingressHostDomain: "${ingressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    ]

    elCicdDefaults = [
        imagePullSecret: imagePullSecret,
        ingressHostDomain: "${ingressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}",
        SDLC_ENV: projectInfo.deployToEnv,
        TEAM_ID: projectInfo.teamInfo.id,
        PROJECT_ID: projectInfo.id
    ]

    def elCicdProfiles = projectInfo.elCicdProfiles.clone()
    return [global: [elCicdProfiles: elCicdProfiles], elCicdDefs: elCicdDefs, elCicdDefaults: elCicdDefaults]
}

def getComponentConfigValues(def projectInfo, def component, def imageRegistry, def configValuesMap) {
    configValuesMap = configValuesMap.clone()
    configValuesMap.elCicdDefaults.objName = component.name

    configValuesMap.elCicdDefaults.image =
        "${imageRegistry}/${projectInfo.id}-${component.name}:${projectInfo.releaseVersion ?: projectInfo.deployToEnv}"

    configValuesMap.elCicdDefs.COMPONENT_NAME = component.name
    configValuesMap.elCicdDefs.CODE_BASE = component.codeBase
    configValuesMap.elCicdDefs.GIT_REPO_NAME = component.gitRepoName
    configValuesMap.elCicdDefs.SRC_COMMIT_HASH = component.srcCommitHash ?: el.cicd.UNDEFINED
    configValuesMap.elCicdDefs.DEPLOYMENT_BRANCH = component.deploymentBranch ?: el.cicd.UNDEFINED

    return configValuesMap
}

def runComponentDeploymentStages(def projectInfo, def components) {
    def helmStages = concurrentUtils.createParallelStages("Deploying", components) { component ->
        dir(component.deploymentDir) {
            sh """
                helm upgrade --install --atomic --history-max=1 --output yaml \
                    -f values.yaml \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    . \
                    --post-renderer ./${el.cicd.EL_CICD_POST_RENDER_KUSTOMIZE} \
                    --post-renderer-args '${projectInfo.elCicdProfiles.join(',')}'

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
        TERMINATING_PODS=\$(oc get pods -n ${projectInfo.deployToNamespace} -l elcicd.io/projectid=${projectInfo.id} -o=${jsonPath} | tr '\n' ' ')
        if [[ "\${TERMINATING_PODS}" ]]
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
            def checkoutBranch = component.deploymentBranch ?: component.gitBranch
            resultsMsgs += component.flaggedForDeployment ? "${component.name} DEPLOYED FROM GIT:" : "${component.name} REMOVED FROM NAMESPACE"
            if (component.flaggedForDeployment) {
                def refs = component.gitBranch.startsWith(component.srcCommitHash) ?
                    "    Git image source ref: ${component.srcCommitHash}" :
                    "    Git image source refs: ${component.gitBranch} / ${component.srcCommitHash}"

                resultsMsgs += "    Git deployment ref: ${checkoutBranch}"
                resultsMsgs += "    git checkout ${checkoutBranch}"
            }
            resultsMsgs += ''
        }
    }
    resultsMsgs += "**********"

    loggingUtils.echoBanner(resultsMsgs)
}