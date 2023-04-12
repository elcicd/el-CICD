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

def runComponentDeploymentStages(def projectInfo, def components) {
    setupComponentDeploymentDirs(projectInfo, components)

    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
    def imageRegistry = el.cicd["${ENV_TO}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def imagePullSecret = "el-cicd-${projectInfo.deployToEnv}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"

    def ingressHostDomain = (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''

    def commonValues = ["elCicdProfiles={${projectInfo.deployToEnv}}",
                        "elCicdDefaults.imagePullSecret=${imagePullSecret}",
                        "elCicdDefaults.ingressHostDomain='${ingressHostDomain}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}'",
                        "elCicdDefs.SDLC_ENV=${projectInfo.deployToEnv}",
                        "elCicdDefs.TEAM_ID=${projectInfo.teamId}",
                        "elCicdDefs.PROJECT_ID=${projectInfo.id}"]

    sh "helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}"

    def helmStages = concurrentUtils.createParallelStages("Component Deployments", components) { component ->
        def componentImage = "${imageRegistry}/${projectInfo.id}-${component.name}:${projectInfo.deployToEnv}"
        def compValues = ["elCicdDefaults.appName=${component.name}",
                          "elCicdDefaults.image=${componentImage}",
                          "elCicdDefs.COMPONENT_NAME=${component.name}"]

        compValues.addAll(commonValues)

        helmUpgradeInstall(projectInfo, component, compValues)
    }

    parallel(helmStages)

    waitForAllTerminatingPodsToFinish(projectInfo)
}

def setupComponentDeploymentDirs(def projectInfo, def componentsToDeploy) {
    def commonValues = ["profiles=${projectInfo.deployToEnv}",
                        "teamId=${projectInfo.teamId}",
                        "projectId=${projectInfo.id}",
                        "releaseVersion=${projectInfo.releaseVersionTag ?: el.cicd.UNDEFINED}",
                        "buildNumber=\${BUILD_NUMBER}",
                        "metaInfoPostfix=${el.cicd.META_INFO_POSTFIX}"]

    componentsToDeploy.each { component ->
        def compValues = ["codeBase=${component.codeBase}",
                          "scmRepoName=${component.scmRepoName}",
                          "srcCommitHash=${component.srcCommitHash}",
                          "deploymentBranch=${component.deploymentBranch ?: el.cicd.UNDEFINED}"]

        if (fileExists("${component.deploymentDir}/${projectInfo.deployToEnv}/kustomization.yaml")) {
            compValues.add("kustDir=${component.deploymentDir}/${projectInfo.deployToEnv}")
        }
        else if (fileExists("${component.deploymentDir}/${el.cicd.KUSTOMIZE_BASE_DIR}/kustomization.yaml")) {
            compValues.add("kustDir=${component.deploymentDir}/${el.cicd.KUSTOMIZE_BASE_DIR}")
        }

        compValues.addAll(commonValues)

        dir (component.deploymentDir) {
            sh """
                cp -rTn ${el.cicd.EL_CICD_DIR}/${el.cicd.TEMPLATE_CHART_DIR} .
                if [[ -d ./${projectInfo.deployToEnv} ]]
                then
                    cp -rT ./${projectInfo.deployToEnv} .
                fi
                cp ${el.cicd.EL_CICD_DIR}/${el.cicd.TEMPLATE_CHART_DIR}/kustomize.sh .

                mkdir -p ${el.cicd.EL_CICD_KUSTOMIZE_DIR}
                ${shCmd.echo ''}
                helm template \
                    --set-string ${compValues.join(' --set-string ')} \
                    -n ${projectInfo.deployToNamespace} \
                    ${component.name} \
                    ${el.cicd.EL_CICD_DIR}/${el.cicd.KUSTOMIZE_CHART_DIR} \
                    > ./${el.cicd.EL_CICD_KUSTOMIZE_DIR}/kustomization.yaml
            """
        }
    }
}

def helmUpgradeInstall(def projectInfo, def component, def compValues) {
    dir("${component.workDir}/${el.cicd.CHART_DEPLOY_DIR}") {
        sh """
            VALUES_FILES=\$(find . -maxdepth 1 -type f \\( -name *values*.yaml -o -name *values*.yml -o -name *values*.json \\) -printf '-f %f ')

            HELM_ARGS="--set-string ${compValues.join(' --set-string ')}
                \${VALUES_FILES}
                -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-component-values.yaml
                -n ${projectInfo.deployToNamespace}
                --post-renderer ./kustomize.sh --post-renderer-args ${el.cicd.EL_CICD_KUSTOMIZE_DIR}
                ${component.name} ."

            ${shCmd.echo ''}
            helm template --dependency-update --debug \${HELM_ARGS}

            ${shCmd.echo ''}
            helm upgrade --atomic --install --history-max=1 \${HELM_ARGS}
        """
    }
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