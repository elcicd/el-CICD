/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the el-CICD framework
 */

def setupTeamCicdServer(def teamInfo) {
    loggingUtils.echoBanner("CONFIGURING JENKINS IN NAMESPACE ${teamInfo.cicdMasterNamespace} FOR TEAM ${teamInfo.id}")

    def jenkinsDefs = getJenkinsConfigValues(teamInfo)
    def jenkinsConfigFile = "jenkins-config-values.yaml"
    writeYaml(file: jenkinsConfigFile, data: jenkinsDefs)

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
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/prod-pipeline-setup-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/el-cicd-jenkins-pipeline-template-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/jenkins-config-values.yaml \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-team-server-values.yaml \
            -n ${teamInfo.cicdMasterNamespace} \
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

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        elCicdDefs.NONPROD_ENVS = []
        elCicdDefs.NONPROD_ENVS.addAll(el.cicd.nonProdEnvs)
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        elCicdDefs.PROD_ENVS = el.cicd.EL_CICD_MASTER_NONPROD ? [el.cicd.prodEnv] : [el.cicd.preProdEnv, el.cicd.prodEnv]
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

def resetProjectPvResources(def projectInfo) {
    def chartName = getProjectPvChartName(projectInfo)
    sh """
        PVS_INSTALLED=\$(helm list --short --filter '${chartName}' -n ${projectInfo.teamInfo.cicdMasterNamespace})
        if [[ "\${PVS_INSTALLED}" ]]
        then
            helm uninstall ${chartName} -n ${projectInfo.teamInfo.cicdMasterNamespace}
        fi
    """
}

def setupProjectPvResources(def projectInfo) {
    if (projectInfo.staticPvs) {
        def pvYaml = getPvCicdConfigValues(projectInfo)
        def volumeCicdConfigValues = writeYaml(data: pvYaml, returnText: true)

        def volumeCicdConfigFile = "volume-cicd-config-values.yaml"
        writeFile(file: volumeCicdConfigFile, text: volumeCicdConfigValues)

        def chartName = getProjectPvChartName(projectInfo)

        sh """
            ${shCmd.echo '', "${projectInfo.id} PROJECT VOLUME VALUES:"}
            cat ${volumeCicdConfigFile}

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
    def projectDefs = getElCicdChartConfigValues(projectInfo)
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
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/project-cicd-setup-values.yaml \
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

def configureDeployKeys(def projectInfo) {
    projectInfoUtils.setRemoteRepoDeployKeyId(projectInfo)

    def buildStages =  concurrentUtils.createParallelStages('Setup SCM deploy keys', projectInfo.modules) { module ->
        withCredentials([sshUserPrivateKey(credentialsId: module.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            sh """
                ${shCmd.echo  '', "--> CREATING NEW GIT DEPLOY KEY FOR: ${module.scmRepoName}", ''}

                source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                _delete_scm_repo_deploy_key ${projectInfo.scmRestApiHost} \
                                            ${projectInfo.scmOrganization} \
                                            ${module.scmRepoName} \
                                            \${GITHUB_ACCESS_TOKEN} \
                                            ${projectInfo.repoDeployKeyId}

                _add_scm_repo_deploy_key ${projectInfo.scmRestApiHost} \
                                        ${projectInfo.scmOrganization} \
                                        ${module.scmRepoName} \
                                        \${GITHUB_ACCESS_TOKEN} \
                                        ${projectInfo.repoDeployKeyId} \
                                        ${module.scmDeployKeyJenkinsId}.pub \
                                        false
            """
        }
    }

    parallel(buildStages)
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

def getElCicdChartConfigValues(def projectInfo) {
    cicdConfigValues = [elCicdDefs: [:]]
    def elCicdDefs= cicdConfigValues.elCicdDefs

    createElCicdProfiles(cicdConfigValues, elCicdDefs)

    getElCicdPipelineChartValues(projectInfo, elCicdDefs)

    getElCicdCodeBaseChartValues(projectInfo, elCicdDefs)

    def rqProfiles = [:]
    def cicdEnvs = []
    elCicdDefs.CICD_NAMESPACES = []
    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        getElCicdNonProdEnvsResourceQuotasValues(projectInfo, elCicdDefs, rqProfiles)

        getElCicdRbacNonProdGroupsValues(projectInfo, elCicdDefs)

        elCicdDefs.CICD_NAMESPACES += projectInfo.nonProdNamespaces.values()
        elCicdDefs.CICD_NAMESPACES += projectInfo.sandboxNamespaces.values()
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        getElCicdProdEnvsResourceQuotasValues(projectInfo, elCicdDefs, rqProfiles)

        getElCicdRbacProdGroupsValues(projectInfo, elCicdDefs)

        elCicdDefs.PROD_NAMESPACES = projectInfo.prodNamespaces.values()
        elCicdDefs.CICD_NAMESPACES += projectInfo.prodNamespaces.values()
    }
    cicdConfigValues.elCicdProfiles += rqProfiles.keySet()

    return cicdConfigValues
}

def getElCicdNamespaceChartValues(def projectInfo, def cicdConfigValues) {
    cicdConfigValues.elCicdNamespaces = []

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        cicdConfigValues.elCicdNamespaces.addAll(projectInfo.nonProdNamespaces.values())
        if (projectInfo.sandboxNamespaces) {
            cicdConfigValues.elCicdNamespaces.addAll(projectInfo.sandboxNamespaces.values())
        }
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        cicdConfigValues.elCicdNamespaces.addAll(projectInfo.prodNamespaces.values())
    }
}


def getElCicdPipelineChartValues(def projectInfo, def elCicdDefs) {
    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        elCicdDefs.NONPROD_ENVS = []
        elCicdDefs.NONPROD_ENVS.addAll(projectInfo.nonProdEnvs)
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        elCicdDefs.PROD_ENV = projectInfo.prodEnv
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

    getElCicdNamespaceChartValues(projectInfo, cicdConfigValues)

    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }

    elCicdDefs.SCM_REPO_SSH_KEY_MODULE_IDS = projectInfo.modules.collect{ module ->
        if (!module.scmDeployKeyJenkinsId) {
            projectInfoUtils.setModuleScmDeployKeyJenkinsId(projectInfo, module)
        }
        return module.scmDeployKeyJenkinsId
    }
}

def getElCicdCodeBaseChartValues(def projectInfo, def elCicdDefs) {
    projectInfo.components.each { component ->
        cicdConfigValues["elCicdDefs-${component.name}"] =
            ['CODE_BASE' : component.codeBase ]
    }

    projectInfo.artifacts.each { art ->
        cicdConfigValues["elCicdDefs-${art.name}"] =
            ['CODE_BASE' : art.codeBase ]
    }
}

def getElCicdNonProdEnvsResourceQuotasValues(def projectInfo, def elCicdDefs, def rqProfiles) {
    cicdEnvs = []
    cicdEnvs += projectInfo.nonProdEnvs
    cicdEnvs += projectInfo.sandboxEnvs

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
}

def getElCicdProdEnvsResourceQuotasValues(def projectInfo, def elCicdDefs, def rqProfiles) {
    cicdEnvs = [projectInfo.prodEnv]
    projectInfo.releaseProfiles.each { profile ->
        cicdEnvs.add("${projectInfo.prodEnv}-${profile}")
    }

    cicdEnvs.each { env ->
        def rqNames = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas[projectInfo.prodEnv]
        rqNames = rqNames ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        rqNames?.each { rqName ->
            elCicdDefs["${rqName}_NAMESPACES"] = elCicdDefs["${rqName}_NAMESPACES"] ?: []
            elCicdDefs["${rqName}_NAMESPACES"] += projectInfo.prodNamespaces[env]
            rqProfiles[rqName] = 'placeHolder'
        }
    }
}

def getElCicdRbacNonProdGroupsValues(def projectInfo, def elCicdDefs) {
    cicdEnvs = []

    cicdEnvs += projectInfo.nonProdEnvs
    cicdEnvs += projectInfo.sandboxEnvs

    cicdEnvs.each { env ->
        def group = projectInfo.rbacGroups[env] ?: projectInfo.defaultRbacGroup
        def namespace = projectInfo.nonProdNamespaces[env] ?: projectInfo.sandboxNamespaces[env]
        elCicdDefs["${namespace}_GROUP"] = group
    }
}

def getElCicdRbacProdGroupsValues(def projectInfo, def elCicdDefs) {
    def group = projectInfo.rbacGroups[projectInfo.prodEnv] ?: projectInfo.defaultRbacGroup
    projectInfo.prodNamespaces.values().each { namespace ->
        elCicdDefs["${namespace}_GROUP"] = group
    }
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

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        configValues.elCicdProfiles += 'nonprod'
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        configValues.elCicdProfiles += 'prod'
    }

    if (el.cicd.OKD_VERSION) {
        configValues.elCicdProfiles += 'okd'
    }
}