/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
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

def removeComponents(def projectInfo, def components) {
    def componentNames = components.collect { it. name }.join('|')
    sh """
        RELEASES=\$(helm list --short --filter '${componentNames}' -n ${projectInfo.deployToNamespace} | tr '\\n' ' ')
        if [[ ! -z \${RELEASES} ]]
        then
            helm uninstall \${RELEASES} --wait  -n ${projectInfo.deployToNamespace}
        else
            ${shCmd.echo 'NOTHING TO CLEAN FROM NAMESPACE; SKIPPING...'}
        fi
    """

    sleep 3

    waitForAllTerminatingPodsToFinish(projectInfo)
}

def setupDeploymentDirs(def projectInfo, def componentsToDeploy) {
    def commonConfigValues = getProjectCommonHelmValues(projectInfo)
    def imageRegistry = el.cicd["${projectInfo.deployToEnv.toUpperCase()}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def componentConfigFile = 'elCicdValues.yaml'
    def tmpValuesFile = 'values.yaml.tmp'
    def releaseVersion = projectInfo.releaseVersion ?: '0.1.0'
    def chartYamlValues = projectInfo.releaseVersion ? 'helm-subchart-yaml-values.yaml' : 'helm-chart-yaml-values.yaml'

    componentsToDeploy.each { component ->
        def compConfigValues = getComponentConfigValues(projectInfo, component, imageRegistry, commonConfigValues)

        def chartYaml = 
        dir("${component.workDir}/${el.cicd.CHART_DEPLOY_DIR}") {
            def postRenderDir = "${el.cicd.KUSTOMIZE_DIR}/${el.cicd.POST_RENDERER_KUSTOMIZE_DIR}"
            dir(postRenderDir) {
                writeYaml(file: componentConfigFile, data: compConfigValues)
            }

            sh """
                rm -f ${tmpValuesFile}
                DIR_ARRAY=(.  ${projectInfo.elCicdProfiles.join(' ')})

                set +e
                VALUES_FILES=\$(find \${DIR_ARRAY[@]} -maxdepth 1 -type f \
                                '(' -name '*values*.yaml' -o -name '*values*.yml' ')' \
                                -exec echo -n ' {}' \\; 2>/dev/null )
                set -e

                helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}
                helm template \${VALUES_FILES/ / -f } -f ${postRenderDir}/${componentConfigFile} \
                    --set createPackagedValuesYaml=true \
                    render-values-yaml elCicdCharts/elCicdChart > ${tmpValuesFile}

                rm -f \${VALUES_FILES}
                mv ${tmpValuesFile} values.yaml

                ${loggingUtils.shellEchoBanner("Final ${component.name} Helm values.yaml")}

                cat values.yaml

                helm template -f ${postRenderDir}/${componentConfigFile} \
                              -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/kust-chart-values.yaml \
                              elCicdCharts/elCicdChart | grep -vE ^[#-] > ${postRenderDir}/kustomization.yaml

                if [[ ! -f Chart.yaml ]]
                then
                    helm template --set-string elCicdDefs.VERSION=${releaseVersion} \
                                  --set-string elCicdDefs.HELM_REPOSITORY_URL=${el.cicd.EL_CICD_HELM_REPOSITORY} \
                                  -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${chartYamlValues} \
                                  elCicdCharts/elCicdChart > Chart.yaml
                fi
                helm dependency update .
                
                cp -R ${el.cicd.EL_CICD_CHARTS_TEMPLATE_DIR} .
                
                cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${el.cicd.COMP_KUST_SH} ${el.cicd.KUSTOMIZE_DIR}
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
    def imagePullSecret = "el-cicd-${projectInfo.deployToEnv}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
    def elCicdDefaults = [
        imagePullSecret: "el-cicd-${projectInfo.deployToEnv}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}",
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
        "${imageRegistry}/${projectInfo.id}-${component.name}:${projectInfo.deployToEnv}"

    configValuesMap.elCicdDefs.COMPONENT_NAME = component.name
    configValuesMap.elCicdDefs.CODE_BASE = component.codeBase
    configValuesMap.elCicdDefs.SCM_REPO_NAME = component.scmRepoName
    configValuesMap.elCicdDefs.SRC_COMMIT_HASH = component.srcCommitHash ?: el.cicd.UNDEFINED
    configValuesMap.elCicdDefs.DEPLOYMENT_BRANCH = component.deploymentBranch ?: el.cicd.UNDEFINED

    return configValuesMap
}

def runComponentDeploymentStages(def projectInfo, def components) {
    def helmStages = concurrentUtils.createParallelStages("Deploying", components) { component ->
        dir(component.deploymentDir) {
            sh """
                helm upgrade --install --atomic  --history-max=1 \
                    -f values.yaml \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    . \
                    --post-renderer ./${el.cicd.KUSTOMIZE_DIR}/${el.cicd.COMP_KUST_SH} \
                    --post-renderer-args '${projectInfo.elCicdProfiles.join(' ')}'

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
        TERMINATING_PODS=\$(oc get pods -n ${projectInfo.deployToNamespace} -l el-cicd.io/projectid=${projectInfo.id} -o=${jsonPath} | tr '\n' ' ')
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