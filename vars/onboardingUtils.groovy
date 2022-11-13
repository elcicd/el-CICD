/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the el-CICD framework
 */

def init() {
    loggingUtils.echoBanner("COPYING ONBOARDING RESOURCES TO JENKINS AGENT")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubDeployKey-template.json", text: libraryResource('templates/githubDeployKey-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-template.xml", text: libraryResource('templates/jenkinsSshCredentials-template.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml", text: libraryResource('templates/jenkinsTokenCredentials-template.xml')
}

def copyPullSecretsToEnvNamespace(def namespace, def env) {
    def secretName = el.cicd["${env.toUpperCase()}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"]
    sh """
        ${shCmd.echo ''}
        oc get secrets ${secretName} -o yaml -n ${el.cicd.ONBOARDING_MASTER_NAMESPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
            oc apply -f - -n ${namespace}

        ${shCmd.echo ''}
    """
}

def createCicdNamespaceAndJenkins(def projectInfo) {
    stage('Creating CICD namespace and Jenkins CICD Server for project') {
        loggingUtils.echoBanner("CREATING ${projectInfo.cicdMasterNamespace} NAMESPACE AND CICD SERVER FOR PROJECT ${projectInfo.id}")

        if (!el.cicd.JENKINS_IMAGE_PULL_SECRET && el.cicd.OKD_VERSION) {
            el.cicd.JENKINS_IMAGE_PULL_SECRET =
                sh(returnStdout: true,
                    script: """
                        oc get secrets -o custom-columns=:metadata.name -n ${projectInfo.cicdMasterNamespace} | \
                        grep deployer-dockercfg | \
                        tr -d '[:space:]'
                    """
                )
        }

        def jenkinsUrl = "jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
        sh """
            ${shCmd.echo ''}
            helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}

            ${shCmd.echo ''}
            helm upgrade --create-namespace --atomic --install --history-max=1 \
                --set-string elCicdDefs.JENKINS_IMAGE=${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_IMAGE_NAME} \
                --set-string elCicdDefs.JENKINS_URL=${jenkinsUrl} \
                --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH="${el.cicd.OKD_VERSION ? 'true' : 'false'}" \
                --set-string elCicdDefs.CPU_LIMIT=${el.cicd.JENKINS_CPU_LIMIT} \
                --set-string elCicdDefs.MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                --set-string elCicdDefs.VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                --set-string elCicdDefs.JENKINS_IMAGE_PULL_SECRET=${el.cicd.JENKINS_IMAGE_PULL_SECRET} \
                --set-string create-namespaces
                -n ${projectInfo.cicdMasterNamespace} \
                -f ${el.cicd.CONFIG_HELM_DIR}/default-project-sdlc-values.yaml \
                -f ${el.cicd.HELM_DIR}/sdlc-pipelines-values.yaml \
                -f ${el.cicd.HELM_DIR}/non-prod-sdlc-setup-values.yaml \
                ${PROJECT_ID} \
                elCicdCharts/elCicdChart

            ${shCmd.echo ''}
            ${shCmd.echo 'Jenkins up, sleep for 5 more seconds to make sure server REST api is ready'}
            sleep 5
        """
    }
}


def createNonProdSdlcNamespacesAndPipelines(def projectInfo) {
    stage('Creating SDLC namespaces and pipelines') {
        loggingUtils.echoBanner("CREATING/UPGRADING THE SLDC ENVIRONMENTS AND RESOURCES FOR PROJECT ${PROJECT_ID}")

        def projectDefs = getSldcConfigValues(projectInfo)
        def sdlcConfigFile = "sdlc-config-values.yaml"
        def sdlcConfigValues = writeYaml(text: elCicdDefs, returnText: true)
        writeFile(file: sdlcConfigFile, text: sdlcConfigValues)
        
        def baseAgentImage = "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"
        def helmCommands = """
            helm upgrade --atomic --install --history-max=1 --debug \
                -n ${projectInfo.cicdMasterNamespace} \
                -f ${sdlcConfigFile} \
                -f ${el.cicd.CONFIG_HELM_DIR}/default-project-sdlc-values.yaml \
                -f ${el.cicd.HELM_DIR}/sdlc-pipelines-values.yaml \
                -f ${el.cicd.HELM_DIR}/non-prod-sdlc-setup-values.yaml \
                ${projectInfo.id} \
                elCicdCharts/elCicdChart

            helm upgrade --wait-for-jobs --install --history-max=1  \
                --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
                -n ${projectInfo.cicdMasterNamespace} \
                -f ${el.cicd.CONFIG_HELM_DIR}/jenkins-pipeline-sync-job-values.yaml \
                jenkins-sync \
                elCicdCharts/elCicdChart
        """
        
        echo helmCommands
                
        sh """
            set +x
            ${helmCommands}
            set -x
        """
    }
}

def getSldcConfigValues(def projectInfo) {
    sdlcConfigValues = [:]
    sdlcConfigValues.createNamespaces = true
    
    elCicdDefs = [:]
    elCicdDefs.SDLC_NAMESPACES = projectInfo.nonProdNamespaces
    
    elCicdDefs.PROJECT_ID = projectInfo.id
    elCicdDefs.SCM_BRANCH = projectInfo.scmBranch
    elCicdDefs.DEV_NAMESPACE = projectInfo.devNamespace
    elCicdDefs.EL_CICD_GIT_REPO = projectInfo.scmRepo
    elCicdDefs.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID = el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    elCicdDefs.EL_CICD_GIT_REPO_BRANCH_NAME = el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME

    def resourceQuotasFlags = projectInfo.nonProdEnvs.findResults { env ->
        rqs = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        rqs?.each { rq ->
            elCicdDefs["${it}_NAMESPACE"] = "${projectInfo.id}-${env}"
        }
    }

    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }

    projectInfo.components.each { comp ->
        elCicdDefs["${el.cicd.EL_CICD_DEFS_TEMPLATE}-${comp.name}-build-to-dev"] = comp.codeBase
    }

    projectInfo.artifacts.each { art ->
        elCicdDefs["${el.cicd.EL_CICD_DEFS_TEMPLATE}-${art.name}-build-artifact"] = art.codeBase
    }

    projectInfo.rbacGroups.each { env, group ->
        elCicdDefs["${el.cicd.EL_CICD_DEFS_TEMPLATE}.${projectInfo.id}-${env}_GROUP"] = group
    }
    
    elCicdDefs.NFS_APP_NAMES = []
    projectInfo.nfsShares.each { nfsShare ->
        nfsShare.envs.each { env ->
            def namepace = projectInfo.nonProdNamespaces[env]
            def appName = "${el.cicd.NFS_PV_PREFIX}-${namespace}-${nfsShare.claimName}"
            elCicdDefs.NFS_APP_NAMES << appName
            
            nfsMap = [:]
            nfsMap.CLAIM_NAME = nfsShare.claimName
            nfsMap.CAPACITY = nfsShare.capacity
            nfsMap.ACESS_MODES = nfsShare.accessModes ? nfsShare.accessModes : [nfsShare.accessMode]
            nfsMap.PATH = nfsShare.exportPath
            nfsMap.SERVER = nfsShare.server
            nfsMap.NAMESPACE = namepace
            
            sdlcConfigValues[appName] = nfsMap
        }
    }
    sdlcConfigValues.elCicdDefs = elCicdDefs
    
    sdlcConfigValues.profiles = []
    sdlcConfigValues.profiles.addAll(projectInfo.resourceQuotas.keys())
    if (projectInfo.nfsShares) {
        sdlcConfigValues.profiles << "nfs"
    }
    
    return sdlcConfigValues
}