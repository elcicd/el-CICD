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
    def rbacGroups = projectInfo.rbacGroups.toMapString()
    loggingUtils.echoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE FOLLOWING GROUPS:", rbacGroups)

    if (!el.cicd.JENKINS_IMAGE_PULL_SECRET && el.cicd.OKD_VERSION) {
        sh """
            if [[ -z \$(oc get project ${projectInfo.cicdMasterNamespace} --no-headers --ignore-not-found) ]]
            then
                oc new-project ${projectInfo.cicdMasterNamespace}
                sleep 3
            fi
        """
        
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
        helm upgrade --atomic --install --history-max=1  --debug \
            --set-string profiles='{sdlc}' \
            --set-string elCicdDefs.JENKINS_IMAGE=${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_IMAGE_NAME} \
            --set-string elCicdDefs.JENKINS_URL=${jenkinsUrl} \
            --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH="${el.cicd.OKD_VERSION ? 'true' : 'false'}" \
            --set-string elCicdDefs.CPU_LIMIT=${el.cicd.JENKINS_CPU_LIMIT} \
            --set-string elCicdDefs.MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
            --set-string elCicdDefs.VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
            --set-string elCicdDefs.JENKINS_IMAGE_PULL_SECRET=${el.cicd.JENKINS_IMAGE_PULL_SECRET} \
            -n ${projectInfo.cicdMasterNamespace} \
            -f ${el.cicd.CONFIG_HELM_DIR}/default-non-prod-cicd-values.yaml \
            -f ${el.cicd.EL_CICD_HELM_DIR}/jenkins-config-values.yaml \
            ${projectInfo.defaultRbacGroup}-jenkins \
            elCicdCharts/elCicdChart

        ${shCmd.echo ''}
        ${shCmd.echo 'Jenkins CICD Server up, sleep for 5 more seconds to make sure server REST api is ready'}
        sleep 5
    """
}


def createNonProdSdlcNamespacesAndPipelines(def projectInfo) {
    loggingUtils.echoBanner("INSTALL/UPGRADE PROJECT ${projectInfo.id} SDLC RESOURCES")

    def projectDefs = getSldcConfigValues(projectInfo)
    def sdlcConfigValues = writeYaml(data: projectDefs, returnText: true)
    
    def sdlcConfigFile = "sdlc-config-values.yaml"
    writeFile(file: sdlcConfigFile, text: sdlcConfigValues)
    
    
    def baseAgentImage = "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"
            
    sh """
        cat ${sdlcConfigFile}
        
        ${shCmd.echo ''}            
        helm upgrade --atomic --install --history-max=1 \
            -f ${sdlcConfigFile} \
            -f ${el.cicd.CONFIG_HELM_DIR}/default-project-sdlc-values.yaml \
            -f ${el.cicd.EL_CICD_HELM_DIR}/non-prod-sdlc-pipelines-values.yaml \
            -f ${el.cicd.EL_CICD_HELM_DIR}/non-prod-sdlc-setup-values.yaml \
            -n ${projectInfo.cicdMasterNamespace} \
            ${projectInfo.id}-sdlc \
            elCicdCharts/elCicdChart

        ${shCmd.echo ''}
        if [[ ! -z \$(helm list -n ${projectInfo.cicdMasterNamespace} | grep jenkins-sync) ]]
        then 
            helm uninstall jenkins-sync -n ${projectInfo.cicdMasterNamespace}
        fi
        
        ${shCmd.echo ''}
        helm upgrade --wait-for-jobs --install --history-max=1 \
            --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
            -n ${projectInfo.cicdMasterNamespace} \
            -f ${el.cicd.EL_CICD_HELM_DIR}/jenkins-pipeline-sync-job-values.yaml \
            jenkins-sync \
            elCicdCharts/elCicdChart
    """
}

def getSldcConfigValues(def projectInfo) {
    sdlcConfigValues = [:]
    sdlcConfigValues.createNamespaces = true
    
    elCicdDefs = [:]
    elCicdDefs.SDLC_ENVS = []
    elCicdDefs.SDLC_ENVS.addAll(projectInfo.nonProdNamespaces.keySet())
    
    elCicdDefs.PROJECT_ID = projectInfo.id
    elCicdDefs.SCM_BRANCH = projectInfo.scmBranch
    elCicdDefs.DEV_NAMESPACE = projectInfo.devNamespace
    elCicdDefs.EL_CICD_GIT_REPO = el.cicd.EL_CICD_GIT_REPO
    elCicdDefs.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID = el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    elCicdDefs.EL_CICD_GIT_REPO_BRANCH_NAME = el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME
    elCicdDefs.ONBOARDING_MASTER_NAMESPACE = el.cicd.ONBOARDING_MASTER_NAMESPACE

    def resourceQuotasFlags = projectInfo.nonProdEnvs.findResults { env ->
        rqs = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        rqs?.each { rq ->
            elCicdDefs["${rq}_NAMESPACE"] = "${projectInfo.id}-${env}"
        }
    }

    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }

    projectInfo.components.each { comp ->
        sdlcConfigValues["elCicdDefs-${comp.name}-build-component"] = ['CODE_BASE' : comp.codeBase ]
    }

    projectInfo.artifacts.each { art ->
        sdlcConfigValues["elCicdDefs-${art.name}-build-artifact"] = ['CODE_BASE' : art.codeBase ]
    }

    projectInfo.nonProdEnvs.each { env ->
        def group = projectInfo.rbacGroups[env] ?: projectInfo.defaultRbacGroup
        elCicdDefs["${projectInfo.id}-${env}_GROUP"] = group
    }
    
    sdlcConfigValues.profiles = []
    sdlcConfigValues.profiles.addAll(projectInfo.resourceQuotas.keySet())
    if (projectInfo.nfsShares) {
        sdlcConfigValues.profiles << "nfs"
        
        elCicdDefs.NFS_APP_NAMES = []
        projectInfo.nfsShares.each { nfsShare ->
            nfsShare.envs.each { env ->
                def namespace = projectInfo.nonProdNamespaces[env]
                if (namespace) {
                    def appName = "${el.cicd.NFS_PV_PREFIX}-${namespace}-${nfsShare.claimName}"
                    elCicdDefs.NFS_APP_NAMES << appName
                    
                    nfsMap = [:]
                    nfsMap.CLAIM_NAME = nfsShare.claimName
                    nfsMap.CAPACITY = nfsShare.capacity
                    nfsMap.ACESS_MODES = nfsShare.accessModes ? nfsShare.accessModes : [nfsShare.accessMode]
                    nfsMap.PATH = nfsShare.exportPath
                    nfsMap.SERVER = nfsShare.server
                    nfsMap.NAMESPACE = namespace
                    
                    sdlcConfigValues["elCicdDefs-${appName}"] = nfsMap
                }
            }
        }
    }
    
    sdlcConfigValues.elCicdDefs = elCicdDefs
    
    return sdlcConfigValues
}