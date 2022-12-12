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
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsUsernamePasswordCreds-template.xml", text: libraryResource('templates/jenkinsUsernamePasswordCreds-template.xml')
}

def setupClusterWithProjecCicdServer(def projectInfo) {
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
    def profiles = el.cicd.OKD_VERSION ? 'cicd,okd' : 'cicd'
    profiles += el.cicd.JENKINS_PERSISTENT ? ',jenkinsPersistent' : ''
    profiles += el.cicd.JENKINS_AGENT_PERSISTENT ? ',jenkinsAgentPersistent' : ''
    
    sh """
        ${shCmd.echo ''}
        helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}
        
        ${shCmd.echo ''}
        helm upgrade --install --history-max=1 \
            --set-string profiles='{${profiles}}' \
            --set-string elCicdDefs.EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} \
            --set-string elCicdDefs.EL_CICD_BUILD_SECRETS_NAME=${el.cicd.EL_CICD_BUILD_SECRETS_NAME} \
            --set-string elCicdDefs.SDLC_ENVS='{${projectInfo.nonProdNamespaces.keySet().join(',')}}' \
            --set-string elCicdDefs.ONBOARDING_MASTER_NAMESPACE=${el.cicd.ONBOARDING_MASTER_NAMESPACE} \
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
            ${projectInfo.defaultRbacGroup}-cicd-server \
            elCicdCharts/elCicdChart
        oc rollout status deploy/jenkins    
        
        ${shCmd.echo ''}
        ${shCmd.echo 'Jenkins CICD Server up, sleep for 5 more seconds to make sure server REST api is ready'}
        sleep 5
    """
}


def setupClusterWithProjectCicdResources(def projectInfo) {
    loggingUtils.echoBanner("CONFIGURE CLUSTER TO SUPPORT NON-PROD PROJECT ${projectInfo.id} SDLC")
    
    def cicdNamespaces = projectInfo.nonProdNamespaces.values().join(',')
    if (projectInfo.sandboxNamespaces) {
        cicdNamespaces += ',' + projectInfo.sandboxNamespaces.values().join(',')
    }

    def projectDefs = getSldcConfigValues(projectInfo)
    def cicdConfigValues = writeYaml(data: projectDefs, returnText: true)
    
    def cicdConfigFile = "cicd-config-values.yaml"
    writeFile(file: cicdConfigFile, text: cicdConfigValues)
    
    def baseAgentImage = "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"
            
    sh """
        cat ${cicdConfigFile}
        
        ${shCmd.echo ''}            
        helm template \
            --set elCicdNamespaces='{${cicdNamespaces}}' \
            -f ${cicdConfigFile} \
            -f ${el.cicd.CONFIG_HELM_DIR}/resource-quotas-values.yaml \
            -f ${el.cicd.CONFIG_HELM_DIR}/default-non-prod-cicd-values.yaml \
            -f ${el.cicd.EL_CICD_HELM_DIR}/non-prod-cicd-pipelines-values.yaml \
            -f ${el.cicd.EL_CICD_HELM_DIR}/non-prod-cicd-setup-values.yaml \
            -n ${projectInfo.cicdMasterNamespace} \
            ${projectInfo.id}-project \
            elCicdCharts/elCicdChart
        exit 1

        ${shCmd.echo ''}
        if [[ ! -z \$(helm list -n ${projectInfo.cicdMasterNamespace} | grep jenkins-pipeline-sync) ]]
        then 
            helm uninstall jenkins-pipeline-sync -n ${projectInfo.cicdMasterNamespace}
        fi
        
        ${shCmd.echo ''}
        helm upgrade --wait --wait-for-jobs --install --history-max=1 \
            --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
            -n ${projectInfo.cicdMasterNamespace} \
            -f ${el.cicd.EL_CICD_HELM_DIR}/jenkins-pipeline-sync-job-values.yaml \
            jenkins-pipeline-sync \
            elCicdCharts/elCicdChart
    """
}

def getSldcConfigValues(def projectInfo) {
    cicdConfigValues = [:]
    
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
    elCicdDefs.EL_CICD_BUILD_SECRETS_NAME = el.cicd.EL_CICD_BUILD_SECRETS_NAME

    projectInfo.nonProdEnvs.each { env ->
        def rqNames = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        if (rqNames) {
            rqNames?.each { rqName ->
                elCicdDefs["${rqName}_NAMESPACES"] = elCicdDefs["${rqName}_NAMESPACES"] ?: []
                elCicdDefs["${rqName}_NAMESPACES"] += projectInfo.nonProdNamespaces[env]
            }
        }
    }
    
    def sandboxRqs = projectInfo.resourceQuotas[el.cicd.SANDBOX] ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
    if (sandboxRqs) {
        projectInfo.sandboxNamespaces.values().each { sandboxNamespace ->
            sandboxRqs.each { rqName ->
                elCicdDefs["${rqName}_NAMESPACES"] = elCicdDefs["${rqName}_NAMESPACES"] ?: []
                elCicdDefs["${rqName}_NAMESPACES"] += sandboxNamespace
            }
        }
    }

    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }

    projectInfo.components.each { comp ->
        cicdConfigValues["elCicdDefs-${comp.name}-build-component"] = ['CODE_BASE' : comp.codeBase ]
    }

    projectInfo.artifacts.each { art ->
        cicdConfigValues["elCicdDefs-${art.name}-build-artifact"] = ['CODE_BASE' : art.codeBase ]
    }

    projectInfo.nonProdEnvs.each { env ->
        def group = projectInfo.rbacGroups[env] ?: projectInfo.defaultRbacGroup
        elCicdDefs["${projectInfo.id}-${env}_GROUP"] = group
    }
    
    cicdConfigValues.profiles = el.cicd.OKD_VERSION ? ['okd'] : []
    cicdConfigValues.profiles.addAll(projectInfo.resourceQuotas.keySet())
    if (projectInfo.nfsShares) {
        cicdConfigValues.profiles << "nfs"
        
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
                    
                    cicdConfigValues["elCicdDefs-${appName}"] = nfsMap
                }
            }
        }
    }
    
    cicdConfigValues.elCicdDefs = elCicdDefs
    
    return cicdConfigValues
}