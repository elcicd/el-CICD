/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the el-CICD framework
 */

def init() {
    loggingUtils.echoBanner("COPYING ONBOARDING RESOURCES TO JENKINS AGENT")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubDeployKey-template.json", text: libraryResource('templates/githubDeployKey-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
}

def setupProjectCicdServer(def projectInfo) {
    def rbacGroups = projectInfo.rbacGroups.toMapString()
    loggingUtils.echoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE FOLLOWING GROUPS:", rbacGroups)

    def jenkinsUrl = "${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    def elCicdProfiles = 'cicd,user-group' + (el.cicd.OKD_VERSION ? ',okd' : '')
    elCicdProfiles += sh(returnStdout: true, script: 'oc get pods -o name -n kube-system | grep sealed-secrets') ? ',sealed-secrets' : ''
    elCicdProfiles += el.cicd.JENKINS_PERSISTENT ? ',jenkinsPersistent' : ''

    sh """
        ${shCmd.echo ''}
        helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}

        ${shCmd.echo ''}
        helm upgrade --atomic --install --create-namespace --history-max=1 \
            --set-string elCicdProfiles='{${elCicdProfiles}}' \
            --set-string elCicdDefs.USER_GROUP=${projectInfo.teamId} \
            --set-string elCicdDefs.EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} \
            --set-string elCicdDefs.EL_CICD_BUILD_SECRETS_NAME=${el.cicd.EL_CICD_BUILD_SECRETS_NAME} \
            --set-string elCicdDefs.CICD_ENVS='{${projectInfo.nonProdNamespaces.keySet().join(',')}}' \
            --set-string elCicdDefs.EL_CICD_MASTER_NAMESPACE=${el.cicd.EL_CICD_MASTER_NAMESPACE} \
            --set-string elCicdDefs.JENKINS_IMAGE=${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_IMAGE_NAME} \
            --set-string elCicdDefs.JENKINS_URL=${jenkinsUrl} \
            --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH="${el.cicd.OKD_VERSION ? 'true' : 'false'}" \
            --set-string elCicdDefs.JENKINS_CPU_REQUEST=${el.cicd.JENKINS_MASTER_CPU_REQUEST} \
            --set-string elCicdDefs.JENKINS_MEMORY_LIMIT=${el.cicd.JENKINS_MASTER_MEMORY_LIMIT} \
            --set-string elCicdDefs.JENKINS_AGENT_CPU_REQUEST=${el.cicd.JENKINS_AGENT_CPU_REQUEST} \
            --set-string elCicdDefs.JENKINS_AGENT_MEMORY_REQUEST=${el.cicd.JENKINS_AGENT_MEMORY_REQUEST} \
            --set-string elCicdDefs.JENKINS_AGENT_MEMORY_LIMIT=${el.cicd.JENKINS_AGENT_MEMORY_LIMIT} \
            --set-string elCicdDefs.VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
            --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${el.cicd.JENKINS_CONFIG_FILE_PATH} \
            --set-file elCicdDefs.JENKINS_CASC_FILE=${el.cicd.CONFIG_JENKINS_DIR}/${el.cicd.JENKINS_CASC_FILE} \
            --set-file elCicdDefs.JENKINS_PLUGINS_FILE=${el.cicd.CONFIG_JENKINS_DIR}/${el.cicd.JENKINS_PLUGINS_FILE} \
            -n ${projectInfo.cicdMasterNamespace} \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-team-server-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/jenkins-config-values.yaml \
            jenkins \
            elCicdCharts/elCicdChart

        ${shCmd.echo ''}
        ${shCmd.echo 'JENKINS UP: sleep for 5 seconds to make sure server REST api is ready'}
        sleep 5
    """
}

def setupProjectNfsPvResources(def projectInfo) {
    if (projectInfo.nfsShares) {
        loggingUtils.echoBanner("CONFIGURE CLUSTER TO SUPPORT NON-PROD PROJECT ${projectInfo.id} CICD NFS Volumes")

        def projectDefs = getNfsCicdConfigValues(projectInfo)
        def nfsCicdConfigValues = writeYaml(data: projectDefs, returnText: true)

        def nfsCicdConfigFile = "nfs-cicd-config-values.yaml"
        writeFile(file: nfsCicdConfigFile, text: nfsCicdConfigValues)

        def chartName = projectInfo.id.endsWith(el.cicd.HELM_RELEASE_PROJECT_SUFFIX) ?
            "${projectInfo.id}-pv" : "${projectInfo.id}-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}-pv"

        sh """
            ${shCmd.echo '', "${projectInfo.id} PROJECT NFS VALUES:"}
            cat ${nfsCicdConfigFile}
            
            PVS_INSTALLED=\$(helm list --short --filter '${chartName}' -n ${projectInfo.cicdMasterNamespace})
            if [[ ! -z \${PVS_INSTALLED} ]]
            then
                helm uninstall ${chartName} -n ${projectInfo.cicdMasterNamespace}
            fi

            if [[ ! -z '${projectInfo.nfsShares ? 'hasPvs' : ''}' ]]
            then
                helm install \
                    -f ${nfsCicdConfigFile} \
                    -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/nfs-pv-values.yaml \
                    -n ${projectInfo.cicdMasterNamespace} \
                    ${chartName} \
                    elCicdCharts/elCicdChart
            fi
        """
    }
}

def setupProjectCicdResources(def projectInfo) {
    loggingUtils.echoBanner("CONFIGURE CLUSTER TO SUPPORT NON-PROD PROJECT ${projectInfo.id} CICD")

    def projectDefs = getCicdConfigValues(projectInfo)
    def cicdConfigValues = writeYaml(data: projectDefs, returnText: true)

    def cicdConfigFile = "cicd-config-values.yaml"
    writeFile(file: cicdConfigFile, text: cicdConfigValues)
    
    def moduleSshKeyDefs = createCompSshKeyValues(projectInfo)
    def moduleSshKeyValues= writeYaml(data: moduleSshKeyDefs, returnText: true)
    
    def cicdSshConfigFile = "module-ssh-values.yaml"
    writeFile(file: cicdSshConfigFile, text: moduleSshKeyValues)

    def chartName = projectInfo.id.endsWith(el.cicd.HELM_RELEASE_PROJECT_SUFFIX) ? projectInfo.id : "${projectInfo.id}-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}"

    sh """
        ${shCmd.echo '', "${projectInfo.id} PROJECT VALUES INJECTED INTO el-CICD HELM CHART:"}
        cat ${cicdConfigFile}

        ${shCmd.echo '', "UPGRADE/INSTALLING cicd pipeline definitions for project ${projectInfo.id}"}
        set +e
        if ! helm upgrade --install --history-max=1  \
            -f ${cicdConfigFile} \
            -f ${cicdSshConfigFile} \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/resource-quotas-values.yaml \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-non-prod-cicd-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/non-prod-cicd-pipelines-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/non-prod-cicd-setup-values.yaml \
            -n ${projectInfo.cicdMasterNamespace} \
            ${chartName} \
            elCicdCharts/elCicdChart
        then
            set -e
            helm uninstall ${chartName} -n ${projectInfo.cicdMasterNamespace}
            exit 1
        fi
        set -e
    """
}

def syncJenkinsPipelines(def cicdMasterNamespace) {
    def baseAgentImage = "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"

    sh """
        ${shCmd.echo '', "SYNCING pipeline definitions for the CICD Server in ${cicdMasterNamespace}"}
        if [[ ! -z \$(helm list -n ${cicdMasterNamespace} | grep jenkins-pipeline-sync) ]]
        then
            helm uninstall jenkins-pipeline-sync -n ${cicdMasterNamespace}
        fi

        ${shCmd.echo ''}
        helm upgrade --wait --wait-for-jobs --install --history-max=1 \
            --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
            --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${el.cicd.JENKINS_CONFIG_FILE_PATH} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/jenkins-pipeline-sync-job-values.yaml \
            -n ${cicdMasterNamespace} \
            jenkins-pipeline-sync \
            elCicdCharts/elCicdChart
    """
}

def getNfsCicdConfigValues(def projectInfo) {
    cicdConfigValues = [:]
    elCicdDefs = [:]
    
    elCicdDefs.NFS_OBJ_NAMES = []
    projectInfo.nfsShares.each { nfsShare ->
        nfsShare.envs.each { env ->
            def namespace = projectInfo.nonProdNamespaces[env]
            if (namespace) {
                def objName = "${el.cicd.NFS_PV_PREFIX}-${namespace}-${nfsShare.claimName}"
                elCicdDefs.NFS_OBJ_NAMES << objName

                nfsMap = [:]
                nfsMap.CLAIM_NAME = nfsShare.claimName
                nfsMap.STORAGE_CAPACITY = nfsShare.capacity
                nfsMap.ACCESS_MODES = nfsShare.accessModes ? nfsShare.accessModes : [nfsShare.accessMode]
                nfsMap.PATH = nfsShare.exportPath
                nfsMap.SERVER = nfsShare.server
                nfsMap.NFS_NAMESPACE = namespace

                cicdConfigValues["elCicdDefs-${objName}"] = nfsMap
            }
        }
    }
    
    cicdConfigValues.elCicdDefs = elCicdDefs
    return cicdConfigValues
}

def getCicdConfigValues(def projectInfo) {
    cicdConfigValues = [:]
    elCicdDefs = [:]
    
    elCicdDefs.EL_CICD_GIT_REPOS_READ_ONLY_KEYS = [
        el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID,
        el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    ]

    elCicdDefs.TEAM_ID = projectInfo.teamId
    elCicdDefs.PROJECT_ID = projectInfo.id
    elCicdDefs.SCM_BRANCH = projectInfo.scmBranch
    elCicdDefs.DEV_NAMESPACE = projectInfo.devNamespace
    elCicdDefs.EL_CICD_GIT_REPO = el.cicd.EL_CICD_GIT_REPO
    elCicdDefs.EL_CICD_GIT_REPO_BRANCH_NAME = el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME
    elCicdDefs.EL_CICD_MASTER_NAMESPACE = el.cicd.EL_CICD_MASTER_NAMESPACE
    elCicdDefs.EL_CICD_BUILD_SECRETS_NAME = el.cicd.EL_CICD_BUILD_SECRETS_NAME
    elCicdDefs.NONPROD_ENVS = []
    elCicdDefs.NONPROD_ENVS.addAll(projectInfo.nonProdEnvs)
    elCicdDefs.DEV_ENV = projectInfo.devEnv

    elCicdDefs.SANDBOX_ENVS = []
    elCicdDefs.SANDBOX_ENVS.addAll(projectInfo.sandboxEnvs)

    elCicdDefs.BUILD_NAMESPACE_CHOICES = projectInfo.buildNamespaces.collect { "\'${it}\'" }.toString()

    elCicdDefs.REDEPLOY_ENV_CHOICES = projectInfo.testEnvs.collect {"\'${it}\'" }
    elCicdDefs.REDEPLOY_ENV_CHOICES.add("\'${projectInfo.preProdEnv}\'")
    elCicdDefs.REDEPLOY_ENV_CHOICES = elCicdDefs.REDEPLOY_ENV_CHOICES.toString()

    cicdConfigValues.elCicdNamespaces = []
    cicdConfigValues.elCicdNamespaces.addAll(projectInfo.nonProdNamespaces.values())
    if (projectInfo.sandboxNamespaces) {
        cicdConfigValues.elCicdNamespaces.addAll(projectInfo.sandboxNamespaces.values())
    }

    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }

    def hasJenkinsAgentPersistent = false
    projectInfo.components.each { comp ->
        cicdConfigValues["elCicdDefs-${comp.name}-build-component"] =
            ['CODE_BASE' : comp.codeBase ]
    }

    projectInfo.artifacts.each { art ->
        cicdConfigValues["elCicdDefs-${art.name}-build-artifact"] =
            ['CODE_BASE' : art.codeBase ]
    }

    elCicdDefs.CICD_NAMESPACES = projectInfo.nonProdNamespaces.values() + projectInfo.sandboxNamespaces.values()

    def cicdEnvs = projectInfo.nonProdEnvs.collect()
    cicdEnvs.addAll(projectInfo.sandboxEnvs)
    def rqProfiles = [:]
    cicdEnvs.each { env ->
        def rqNames = projectInfo.resourceQuotas[env]
        rqNames = !rqNames && !projectInfo.nonProdNamespaces[env] ? projectInfo.resourceQuotas[el.cicd.SANDBOX] : rqNames
        rqNames = rqNames ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        rqNames?.each { rqName ->
            elCicdDefs["${rqName}_NAMESPACES"] = elCicdDefs["${rqName}_NAMESPACES"] ?: []
            elCicdDefs["${rqName}_NAMESPACES"] += (projectInfo.nonProdNamespaces[env] ?: projectInfo.sandboxNamespaces[env])
            rqProfiles[rqName] = 'placeHolder'
        }
    }

    cicdEnvs.each { env ->
        def group = projectInfo.rbacGroups[env] ?: projectInfo.defaultRbacGroup
        def namespace = projectInfo.nonProdNamespaces[env] ?: projectInfo.sandboxNamespaces[env]
        elCicdDefs["${namespace}_GROUP"] = group
    }

    cicdConfigValues.elCicdProfiles = (el.cicd.OKD_VERSION ? ['cicd', 'okd'] : ['cicd']) + rqProfiles.keySet()
    
    elCicdDefs.SCM_REPO_SSH_KEY_MODULE_IDS = projectInfo.modules.collect{ it.scmDeployKeyJenkinsId }

    cicdConfigValues.elCicdDefs = elCicdDefs
    return cicdConfigValues
}

def createCompSshKeyValues(def projectInfo) {
    cicdConfigValues = [:]
    
    projectInfo.modules.each { module ->
        dir(module.workDir) {
            echo "Creating deploy key for ${module.scmRepoName}"

            sh "ssh-keygen -b 2048 -t rsa -f '${module.scmDeployKeyJenkinsId}' -q -N '' 2>/dev/null <<< y >/dev/null"
            
            def sshKey = readFile(file: module.scmDeployKeyJenkinsId)            
            
            cicdConfigValues["elCicdDefs-${module.scmDeployKeyJenkinsId}"] = ['SCM_REPO_SSH_KEY': sshKey ]
        }
    }
    
    return cicdConfigValues
}