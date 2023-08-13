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

def setupTeamCicdServer(def teamInfo) {
    loggingUtils.echoBanner("CONFIGURING JENKINS IN NAMESPACE ${teamInfo.cicdMasterNamespace} FOR TEAM ${teamInfo.id}")

    def jenkinsDefs = getJenkinsConfigValues(teamInfo)
    def jenkinsConfigFile = "jenkins-config-values.yaml"
    def jenkinsConfigValues = writeYaml(file: jenkinsConfigFile, data: jenkinsDefs)
    
    sh """
        ${shCmd.echo ''}
        helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}
        
        ${shCmd.echo ''}
        cat ${jenkinsConfigFile}

        ${shCmd.echo ''}
        helm upgrade --install --atomic --create-namespace --history-max=1 --timeout 10m0s \
            -f ${jenkinsConfigFile} \
            --set-file elCicdDefs.JENKINS_CASC_FILE=${el.cicd.CONFIG_JENKINS_DIR}/${el.cicd.JENKINS_CICD_CASC_FILE} \
            --set-file elCicdDefs.JENKINS_PLUGINS_FILE=${el.cicd.CONFIG_JENKINS_DIR}/${el.cicd.JENKINS_CICD_PLUGINS_FILE} \
            -n ${teamInfo.cicdMasterNamespace} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/prod-cicd-setup-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/el-cicd-jenkins-pipeline-template-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/jenkins-config-values.yaml \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-team-server-values.yaml \
            jenkins \
            elCicdCharts/elCicdChart

        sleep 3
        ${shCmd.echo ''}
        ${shCmd.echo 'JENKINS UP'}
    """
}

def getJenkinsConfigValues(def teamInfo) {
    jenkinsConfigValues = [elCicdDefs: [:]]
    def elCicdDefs= jenkinsConfigValues.elCicdDefs
    
    createElCicdProfiles(jenkinsConfigValues, elCicdDefs)
    jenkinsConfigValues.elCicdProfiles += ['user-group']
    
    if (el.cicd.EL_CICD_MASTER_NONPROD?.toBoolean()) {
        elCicdDefs.NONPROD_ENVS = []
        elCicdDefs.NONPROD_ENVS.addAll(el.cicd.nonProdEnvs)
    }
    
    if (el.cicd.EL_CICD_MASTER_PROD?.toBoolean()) {
        elCicdDefs.PROD_ENVS = el.cicd.EL_CICD_MASTER_NONPROD?.toBoolean() ? [el.cicd.prodEnv] : [el.cicd.preProdEnv, el.cicd.prodEnv]
    }
    
    elCicdDefs.EL_CICD_GIT_REPOS_READ_ONLY_KEYS = [
        el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID,
        el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    ]
    
    elCicdDefs.USER_GROUP = teamInfo.id
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME
    elCicdDefs.EL_CICD_BUILD_SECRETS_NAME = el.cicd.EL_CICD_BUILD_SECRETS_NAME
    elCicdDefs.EL_CICD_MASTER_NAMESPACE = el.cicd.EL_CICD_MASTER_NAMESPACE
    elCicdDefs.JENKINS_IMAGE = "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_IMAGE_NAME}"
    elCicdDefs.JENKINS_URL = "${teamInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    elCicdDefs.JENKINS_UC = el.cicd.JENKINS_UC
    elCicdDefs.JENKINS_UC_INSECURE = el.cicd.JENKINS_UC_INSECURE
    elCicdDefs.OPENSHIFT_ENABLE_OAUTH = el.cicd.OKD_VERSION ? 'true' : 'false'
    elCicdDefs.JENKINS_CPU_REQUEST = el.cicd.JENKINS_CICD_CPU_REQUEST
    elCicdDefs.JENKINS_MEMORY_REQUEST = el.cicd.JENKINS_CICD_MEMORY_REQUEST
    elCicdDefs.JENKINS_MEMORY_LIMIT = el.cicd.JENKINS_CICD_MEMORY_LIMIT
    elCicdDefs.JENKINS_AGENT_CPU_REQUEST = el.cicd.JENKINS_AGENT_CPU_REQUEST
    elCicdDefs.JENKINS_AGENT_MEMORY_REQUEST = el.cicd.JENKINS_AGENT_MEMORY_REQUEST
    elCicdDefs.JENKINS_AGENT_MEMORY_LIMIT = el.cicd.JENKINS_AGENT_MEMORY_LIMIT
    elCicdDefs.VOLUME_CAPACITY = el.cicd.JENKINS_CICD_VOLUME_CAPACITY
    elCicdDefs.JENKINS_CONFIG_FILE_PATH = el.cicd.JENKINS_CONFIG_FILE_PATH
    
    return jenkinsConfigValues
}

def setupProjectPvResources(def projectInfo) {
    if (projectInfo.staticPvs) {
        loggingUtils.echoBanner("CONFIGURE CLUSTER TO SUPPORT NON-PROD STATIC PERSISTENT VOLUMES FOR ${projectInfo.id}")

        def pvYaml = getPvCicdConfigValues(projectInfo)
        def volumeCicdConfigValues = writeYaml(data: pvYaml, returnText: true)

        def volumeCicdConfigFile = "volume-cicd-config-values.yaml"
        writeFile(file: volumeCicdConfigFile, text: volumeCicdConfigValues)

        def chartName = getProjectPvChartName(projectInfo)

        sh """
            ${shCmd.echo '', "${projectInfo.id} PROJECT VOLUME VALUES:"}
            cat ${volumeCicdConfigFile}
            
            PVS_INSTALLED=\$(helm list --short --filter '${chartName}' -n ${projectInfo.teamInfo.cicdMasterNamespace})
            if [[ ! -z \${PVS_INSTALLED} ]]
            then
                helm uninstall --wait ${chartName} -n ${projectInfo.teamInfo.cicdMasterNamespace}
            fi

            if [[ ! -z '${projectInfo.staticPvs ? 'hasPvs' : ''}' ]]
            then
                helm install \
                    -f ${volumeCicdConfigFile} \
                    -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/project-pv-values.yaml \
                    -n ${projectInfo.teamInfo.cicdMasterNamespace} \
                    ${chartName} \
                    elCicdCharts/elCicdChart
            fi
        """
    }
}

def getProjectPvChartName(def projectInfo) {
    def chartName = projectInfo.id.endsWith(el.cicd.HELM_RELEASE_PROJECT_SUFFIX) ?
        "${projectInfo.id}-pv" : "${projectInfo.id}-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}-pv"
}

def setupProjectCicdResources(def projectInfo) {
    loggingUtils.echoBanner("CONFIGURE CLUSTER TO SUPPORT NON-PROD PROJECT ${projectInfo.id} CICD")

    def projectDefs = getCicdConfigValues(projectInfo)
    def cicdConfigFile = "cicd-config-values.yaml"
    writeYaml(file: cicdConfigFile, data: projectDefs)
    
    def moduleSshKeyDefs = createCompSshKeyValues(projectInfo)
    def cicdSshConfigFile = "module-ssh-values.yaml"
    writeYaml(file: cicdSshConfigFile, data: moduleSshKeyDefs)

    def chartName = projectInfo.id.endsWith(el.cicd.HELM_RELEASE_PROJECT_SUFFIX) ?
        projectInfo.id : "${projectInfo.id}-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}"

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
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/non-prod-cicd-setup-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/el-cicd-jenkins-pipeline-template-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/scm-secret-values.yaml \
            -n ${projectInfo.teamInfo.cicdMasterNamespace} \
            ${chartName} \
            elCicdCharts/elCicdChart
        then
            set -e
            helm uninstall ${chartName} -n ${projectInfo.teamInfo.cicdMasterNamespace}
            exit 1
        fi
        set -e
    """
}

def syncJenkinsPipelines(def cicdMasterNamespace) {
    def baseAgentImage = "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"

    sh """
        ${shCmd.echo '', "SYNCING pipeline definitions for the CICD Server in ${cicdMasterNamespace}"}
        if [[ ! -z \$(helm list -n ${cicdMasterNamespace} | grep sync-jenkins-pipelines) ]]
        then
            helm uninstall sync-jenkins-pipelines -n ${cicdMasterNamespace}
        fi

        ${shCmd.echo ''}
        helm upgrade --wait --wait-for-jobs --install --history-max=1 \
            --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
            --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${el.cicd.JENKINS_CONFIG_FILE_PATH} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/sync-jenkins-pipelines-job-values.yaml \
            -n ${cicdMasterNamespace} \
            sync-jenkins-pipelines \
            elCicdCharts/elCicdChart
    """
}

def getPvCicdConfigValues(def projectInfo) {
    cicdConfigValues = [:]
    elCicdDefs = [:]
    
    elCicdDefs.VOLUME_OBJ_NAMES = []
    projectInfo.staticPvs.each { pv ->
        pv.envs.each { env ->
            def namespace = projectInfo.nonProdNamespaces[env]
            if (namespace) {
                projectInfo.components.each { component ->
                    if (component.staticPvs.contains(pv.name)) {
                        def objName = "${el.cicd.PV_PREFIX}-${pv.name}-${component.name}-${env}"
                        elCicdDefs.VOLUME_OBJ_NAMES << objName

                        volumeMap = [:]
                        volumeMap.CLAIM_NAME = pv.claimName 
                        volumeMap.VOLUME_MODE = pv.volumeMode
                        volumeMap.RECLAIM_POLICY = pv.reclaimPolicy
                        volumeMap.STORAGE_CAPACITY = pv.capacity
                        volumeMap.ACCESS_MODES = pv.accessModes ? pv.accessModes : [pv.accessMode]
                        volumeMap.VOLUME_NAMESPACE = namespace
                        volumeMap.VOLUME_TYPE = pv.volumeType
                        volumeMap.VOLUME_DEF = pv.volumeDef

                        cicdConfigValues["elCicdDefs-${objName}"] = volumeMap
                    }
                }
            }
        }
    }
    
    cicdConfigValues.elCicdDefs = elCicdDefs
    return cicdConfigValues
}

def getCicdConfigValues(def projectInfo) {
    cicdConfigValues = [elCicdDefs: [:]]
    def elCicdDefs= cicdConfigValues.elCicdDefs
    
    createElCicdProfiles(cicdConfigValues, elCicdDefs)
    
    if (el.cicd.EL_CICD_MASTER_NONPROD?.toBoolean()) {
        elCicdDefs.NONPROD_ENVS = []
        elCicdDefs.NONPROD_ENVS.addAll(projectInfo.nonProdEnvs)
    }

    elCicdDefs.TEAM_ID = projectInfo.teamInfo.id
    elCicdDefs.PROJECT_ID = projectInfo.id
    elCicdDefs.FOLDER_NAME = projectInfo.id
    elCicdDefs.SCM_BRANCH = projectInfo.scmBranch
    elCicdDefs.DEV_NAMESPACE = projectInfo.devNamespace
    elCicdDefs.EL_CICD_GIT_REPO = el.cicd.EL_CICD_GIT_REPO
    elCicdDefs.EL_CICD_GIT_REPO_BRANCH_NAME = el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME
    elCicdDefs.EL_CICD_MASTER_NAMESPACE = el.cicd.EL_CICD_MASTER_NAMESPACE
    elCicdDefs.EL_CICD_BUILD_SECRETS_NAME = el.cicd.EL_CICD_BUILD_SECRETS_NAME
    elCicdDefs.DEV_ENV = projectInfo.devEnv

    elCicdDefs.BUILD_NAMESPACE_CHOICES = projectInfo.buildNamespaces.collect { "\"${it}\"" }.toString()

    elCicdDefs.REDEPLOY_ENV_CHOICES = projectInfo.testEnvs.collect { "\"${it}\"" }
    elCicdDefs.REDEPLOY_ENV_CHOICES.add("\"${projectInfo.preProdEnv}\"")
    elCicdDefs.REDEPLOY_ENV_CHOICES = elCicdDefs.REDEPLOY_ENV_CHOICES.toString()

    cicdConfigValues.elCicdNamespaces = []
    cicdConfigValues.elCicdNamespaces.addAll(projectInfo.nonProdNamespaces.values())
    if (projectInfo.sandboxNamespaces) {
        cicdConfigValues.elCicdNamespaces.addAll(projectInfo.sandboxNamespaces.values())
    }

    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }
    
    elCicdDefs.SCM_REPO_SSH_KEY_MODULE_IDS = projectInfo.modules.collect{ module ->
        if (!module.scmDeployKeyJenkinsId) { 
            projectInfoUtils.setModuleScmDeployKeyJenkinsId(projectInfo, module)
        }
        return module.scmDeployKeyJenkinsId 
    }

    def hasJenkinsAgentPersistent = false
    projectInfo.components.each { component ->
        cicdConfigValues["elCicdDefs-${component.name}"] =
            ['CODE_BASE' : component.codeBase ]
    }

    projectInfo.artifacts.each { art ->
        cicdConfigValues["elCicdDefs-${art.name}"] =
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
    cicdConfigValues.elCicdProfiles += rqProfiles.keySet()

    cicdEnvs.each { env ->
        def group = projectInfo.rbacGroups[env] ?: projectInfo.defaultRbacGroup
        def namespace = projectInfo.nonProdNamespaces[env] ?: projectInfo.sandboxNamespaces[env]
        elCicdDefs["${namespace}_GROUP"] = group
    }

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

def createElCicdProfiles(def configValues, def elCicdDefs) {    
    configValues.elCicdProfiles = ['cicd']
    
    if (el.cicd.EL_CICD_MASTER_NONPROD?.toBoolean()) {
        configValues.elCicdProfiles += 'nonprod'
    }
    
    if (el.cicd.EL_CICD_MASTER_PROD?.toBoolean()) {
        configValues.elCicdProfiles += 'prod'
    }
    
    if (el.cicd.OKD_VERSION) {
        configValues.elCicdProfiles += 'okd'
    }
}