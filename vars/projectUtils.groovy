/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * General pipeline utilities
 */

def gatherProjectInfoStage(def teamId, def projectId) {
    def projectInfo
    stage('Gather project information') {
        loggingUtils.echoBanner("GATHER PROJECT INFORMATION FOR ${projectId} IN ${teamId}")

        projectInfo = gatherProjectInfo(teamId, projectId)
    }

    return projectInfo
}

def gatherProjectInfo(def teamId, def projectId) {
    assert teamId && projectId : "teamId (${teamId}) and (${projectId}) cannot be empty"

    def projectInfo = readProjectYaml(teamId, projectId)

    projectInfo.teamId = teamId
    projectInfo.id = projectId

    projectInfo.repoDeployKeyId = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}|${projectInfo.id}"

    initProjectModuleData(projectInfo)

    initProjectEnvNamespaceData(projectInfo)

    initProjectSandboxData(projectInfo)

    projectInfo.resourceQuotas = projectInfo.resourceQuotas ?: [:]
    projectInfo.staticPvs = projectInfo.staticPvs ?: []

    projectInfo.defaultRbacGroup = projectInfo.rbacGroups[el.cicd.DEFAULT] ?: projectInfo.rbacGroups[projectInfo.devEnv]
    projectInfo.cicdMasterNamespace = "${projectInfo.teamId}-${el.cicd.EL_CICD_MASTER_NAMESPACE}"

    validateProjectInfo(projectInfo)

    return projectInfo
}

def readProjectYaml(def teamId, def projectId) {
    def projectInfo
    dir (el.cicd.PROJECT_DEFS_DIR) {
        def projectFile = findFiles(glob: "**/${teamId}/${projectId}.yaml")
        projectFile = projectFile ?: findFiles(glob: "**/${projectId}.yml")
        projectFile = projectFile ?: findFiles(glob: "**/${projectId}.json")

        if (projectFile) {
            projectInfo = readYaml file: projectFile[0].path
        }
        else {
            loggingUtils.errorBanner("PROJECT NOT FOUND: ${teamId}/${projectId}")
        }
    }

    return projectInfo
}

def initProjectModuleData(def projectInfo) {
    projectInfo.workDir =  "${WORKSPACE}/${projectInfo.id}"

    projectInfo.components = projectInfo.components ?: []
    projectInfo.deploymentDirs = [:]
    projectInfo.components.each { component ->
        component.isComponent = true
        setModuleData(projectInfo, component)
        
        component.deploymentDir = "${component.workDir}/${el.cicd.CHART_DEPLOY_DIR}"
        projectInfo.deploymentDirs[component.name] = "${projectInfo.workDir}/${component.name}"
    }

    projectInfo.artifacts = projectInfo.artifacts ?: []
    projectInfo.artifacts.each { artifact ->
        artifact.isArtifact = true
        setModuleData(projectInfo, artifact)
    }

    projectInfo.testModules = projectInfo.testModules ?: []
    projectInfo.testModules.each { testModule ->
        testModule.isTestModule = true
        setModuleData(projectInfo, testModule)
    }
    
    projectInfo.buildModules = []
    projectInfo.buildModules.addAll(projectInfo.components)
    projectInfo.buildModules.addAll(projectInfo.artifacts)

    projectInfo.modules = []
    projectInfo.modules.addAll(projectInfo.components)
    projectInfo.modules.addAll(projectInfo.artifacts)
    projectInfo.modules.addAll(projectInfo.testModules)
}

def setModuleData(def projectInfo, def module) {
    module.projectInfo = projectInfo

    module.name = module.scmRepoName.toLowerCase().replaceAll(/[^-0-9a-z]/, '-')
    module.id = "${projectInfo.id}-${module.name}"

    module.workDir = "${WORKSPACE}/${module.scmRepoName}"

    module.repoUrl = "git@${projectInfo.scmHost}:${projectInfo.scmOrganization}/${module.scmRepoName}.git"
    module.scmBranch = projectInfo.scmBranch
    module.scmDeployKeyJenkinsId = "${module.name}-${el.cicd.SCM_CREDS_POSTFIX}"
}

def initProjectEnvNamespaceData(def projectInfo) {
    projectInfo.devEnv = el.cicd.devEnv

    projectInfo.hotfixEnv = el.cicd.hotfixEnv

    projectInfo.testEnvs = (el.cicd.testEnvs && projectInfo.enabledTestEnvs) ?
        el.cicd.testEnvs.findAll { projectInfo.enabledTestEnvs.contains(it) } : []

    projectInfo.preProdEnv = el.cicd.preProdEnv
    projectInfo.prodEnv = el.cicd.prodEnv

    projectInfo.nonProdEnvs = [projectInfo.devEnv]
    if (projectInfo.allowsHotfixes) {
        projectInfo.nonProdEnvs << projectInfo.hotfixEnv
    }
    projectInfo.nonProdEnvs.addAll(projectInfo.testEnvs)
    projectInfo.nonProdEnvs.add(projectInfo.preProdEnv)

    projectInfo.DEV_ENV = el.cicd.DEV_ENV
    projectInfo.HOTFIX_ENV = el.cicd.HOTFIX_ENV
    projectInfo.PRE_PROD_ENV = el.cicd.PRE_PROD_ENV
    projectInfo.PROD_ENV = el.cicd.PROD_ENV

    projectInfo.TEST_ENVS = projectInfo.testEnvs.collect { it.toUpperCase() }
    projectInfo.NON_PROD_ENVS = projectInfo.nonProdEnvs.collect { it.toUpperCase() }

    projectInfo.devNamespace = "${projectInfo.id}-${projectInfo.devEnv}"
    projectInfo.preProdNamespace = "${projectInfo.id}-${projectInfo.preProdEnv}"
    projectInfo.prodNamespace = "${projectInfo.id}-${projectInfo.prodEnv}"

    projectInfo.hotfixNamespace = "${projectInfo.id}-${projectInfo.hotfixEnv}"

    projectInfo.nonProdNamespaces = [(projectInfo.devEnv): projectInfo.devNamespace]
    if (projectInfo.allowsHotfixes) {
        projectInfo.nonProdNamespaces[projectInfo.hotfixEnv] = projectInfo.hotfixNamespace
    }

    projectInfo.testEnvs.each { env ->
        projectInfo.nonProdNamespaces[env] = "${projectInfo.id}-${env}"
    }
    projectInfo.nonProdNamespaces[projectInfo.preProdEnv] = projectInfo.preProdNamespace

    projectInfo.buildNamespaces = [projectInfo.devNamespace]
    projectInfo.allowsHotfixes && projectInfo.buildNamespaces << projectInfo.hotfixNamespace
}

def initProjectSandboxData(def projectInfo) {
    def sandboxes = projectInfo.sandboxEnvs ?: 0
    projectInfo.sandboxEnvs = []
    projectInfo.sandboxNamespaces = [:]
    if (sandboxes) {
        (1..sandboxes).each { i ->
            def sandboxEnv = "${el.cicd.SANDBOX_NAMESPACE_PREFIX}-${i}"
            projectInfo.sandboxEnvs << sandboxEnv
            projectInfo.sandboxNamespaces[sandboxEnv] = "${projectInfo.id}-${sandboxEnv}"
        }

        projectInfo.buildNamespaces.addAll(projectInfo.sandboxNamespaces.values())
    }
}

def setProjectReleaseVersion(def projectInfo, def releaseCandidateTag) {
    assert releaseCandidateTag ==~ el.cicd.RELEASE_CANDIDATE_TAG_REGEX:
        "Release Candidate tag  must match the pattern ${el.cicd.RELEASE_CANDIDATE_TAG_REGEX}: ${releaseCandidateTag}"

    projectInfo.releaseCandidateTag = releaseCandidateTag
    projectInfo.releaseVersionTag = "${el.cicd.RELEASE_VERSION_PREFIX}${releaseCandidateTag}"
}

def validateProjectInfo(def projectInfo) {
    assert projectInfo.rbacGroups : 'missing rbacGroups'

    def errMsg = "missing ${projectInfo.devEnv} rbacGroup: this is the default RBAC group for all environments if not otherwise specified"
    assert projectInfo.defaultRbacGroup : errMsg

    assert projectInfo.scmHost ==~
        /^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\-]*[A-Za-z0-9])$/ :
        "bad or missing scmHost '${projectInfo.scmHost}"
    assert projectInfo.scmRestApiHost ==~
        /^(([\p{Alnum}]|[\p{Alnum}][\p{Alnum}-]*[\p{Alnum}])\.)*([\p{Alnum}]|[\p{Alnum}][\p{Alnum}-]*[\p{Alnum}])(\/[\p{Alnum}][\p{Alnum}-]*)*$/ :
        "bad or missing scmRestApiHost '${projectInfo.scmHost}"
    assert projectInfo.scmOrganization : "missing scmOrganization"
    assert projectInfo.scmBranch : "missing git branch"
    assert ((projectInfo.modules.size() - projectInfo.testModules.size()) > 0) : "No components or artifacts defined"

    projectInfo.modules.each { module ->
        assert module.scmRepoName ==~ /[\w-.]+/ : "bad git repo name for component, [\\w-.]+: ${module.scmRepoName}"
        assert module.codeBase ==~ /[a-z-]+/ : "bad codeBase name, [a-z-]+: ${module.codeBase}"
    }

    projectInfo.testModules.each { testModule ->
        testModule.componentRepos.each { scmRepoName ->
            assert projectInfo.components.find { it.scmRepoName == scmRepoName }  : "System test has undefined component repo ${scmRepoName}"
        }
    }

    projectInfo.enabledTestEnvs.each { env ->
        assert el.cicd.testEnvs.contains(env) : "test environment '${env}' must be in [${el.cicd.testEnvs}]"
    }

    projectInfo.staticPvs.each { pv ->
        validateProjectPvs(projectInfo, pv)
    }
}

def validateProjectPvs(def projectInfo, def pv) {
    assert pv.envs : "missing persistent volume environments"
    pv.envs.each { env ->
        assert projectInfo.nonProdEnvs.contains(env) || env == projectInfo.prodEnv
    }

    assert pv.capacity ==~ /\d{1,4}(Mi|Gi)/ : "pv.capacity missing or invalid format \\d{1,4}(Mi|Gi): '${pv.capacity}'"
    assert pv.accessMode ==~
        /(ReadWriteOnce|ReadWriteMany|ReadOnly)/ :
        "missing or invalid pv.accessMode (ReadWriteOnce|ReadWriteMany|ReadOnly): '${pv.accessMode}'"
    assert pv.volumeType ==~ /\w+/ : "missing volume type, pv.volumeType: '${pv.volumeType}'"
    assert pv.volumeDef : "missing volume definition, pv.volumeDef"
    assert pv.claimName: "missing volume claim name, pv.claimName"
}

def cloneGitRepo(def module, def gitReference) {
    dir (module.workDir) {
        checkout([$class: 'GitSCM',
                  branches: [[ name: gitReference ]],
                  userRemoteConfigs: [[ credentialsId: module.scmDeployKeyJenkinsId, url: module.repoUrl ]]
               ])

        def currentHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
        module.srcCommitHash = module.srcCommitHash ?: currentHash
    }
}

def getNonProdDeploymentBranchName(def projectInfo, def component, def deploymentEnv) {
    return (projectInfo.testEnvs.contains(deploymentEnv) || deploymentEnv == el.cicd.preProdEnv) ?
        "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${deploymentEnv}-${component.srcCommitHash}" : ''
}