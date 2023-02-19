/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * General pipeline utilities
 */
 
def gatherProjectInfoStage(def projectId) {
    def projectInfo
    stage('Gather project information') {
        loggingUtils.echoBanner("GATHER PROJECT INFORMATION FOR ${projectId}")
        
        projectInfo = readProjectYaml(projectId)

        projectInfo.id = projectId
        
        projectInfo.repoDeployKeyId = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}|${projectInfo.id}"
        
        initProjectModuleData(projectInfo)
        
        initProjectEnvNamespaceData(projectInfo)
        
        initProjectSandboxData(projectInfo)

        projectInfo.resourceQuotas = projectInfo.resourceQuotas ?: [:]
        projectInfo.nfsShares = projectInfo.nfsShares ?: []

        projectInfo.defaultRbacGroup = projectInfo.rbacGroups[el.cicd.DEFAULT] ?: projectInfo.rbacGroups[projectInfo.devEnv]
        projectInfo.cicdMasterNamespace = "${projectInfo.defaultRbacGroup}-${el.cicd.CICD_MASTER_NAMESPACE_POSTFIX}"
    }

    validateProjectInfo(projectInfo)
    
    return projectInfo
}

def readProjectYaml(def projectId) {
    assert projectId
    
    def projectInfo
    dir (el.cicd.PROJECT_DEFS_DIR) {
        def projectFile = findFiles(glob: "**/${projectId}.yaml")
        projectFile = projectFile ?: findFiles(glob: "**/${projectId}.yml")
        projectFile = projectFile ?: findFiles(glob: "**/${projectId}.json")

        if (projectFile) {
            projectInfo = readYaml file: projectFile[0].path
        }
        else {
            loggingUtils.errorBanner("PROJECT NOT FOUND: ${projectId}")
        }
    }

    return projectInfo
}

def initProjectModuleData(def projectInfo) {
    projectInfo.components = projectInfo.components ?: []
    projectInfo.artifacts = projectInfo.artifacts ?: []
    projectInfo.testModules = projectInfo.testModules ?: []

    projectInfo.buildModules = []
    projectInfo.buildModules.addAll(projectInfo.components)
    projectInfo.buildModules.addAll(projectInfo.artifacts)

    projectInfo.modules = []
    projectInfo.modules.addAll(projectInfo.components)
    projectInfo.modules.addAll(projectInfo.artifacts)
    projectInfo.modules.addAll(projectInfo.testModules)

    projectInfo.buildModules = []
    projectInfo.buildModules.addAll(projectInfo.components)
    projectInfo.buildModules.addAll(projectInfo.artifacts)

    projectInfo.modules.each { module ->
        module.projectId = projectInfo.id
        module.name = module.scmRepoName.toLowerCase().replaceAll(/[^-0-9a-z]/, '-')
        module.id = "${projectInfo.id}-${module.name}"

        module.workDir = "${WORKSPACE}/${module.scmRepoName}"

        module.repoUrl = "git@${projectInfo.scmHost}:${projectInfo.scmOrganization}/${module.scmRepoName}.git"
        module.scmBranch = projectInfo.scmBranch
        module.scmDeployKeyJenkinsId = "${module.id}-${el.cicd.SCM_CREDS_POSTFIX}"

        module.isComponent = projectInfo.components.contains(module)
        module.isArtifact = projectInfo.artifacts.contains(module)
        module.isTestModule = projectInfo.testModules.contains(module)
    }
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

    projectInfo.builderNamespaces = [projectInfo.devNamespace]
    projectInfo.allowsHotfixes && projectInfo.builderNamespaces << projectInfo.hotfixNamespace
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
        
        projectInfo.builderNamespaces.addAll(projectInfo.sandboxNamespaces.values())
    }
}

def setProjectReleaseVersion(def projectInfo, def releaseCandidateTag) {
    echo ''
    echo "releaseCandidateTag: '${releaseCandidateTag}'"
    echo "RELEASE_CANDIDATE_TAG_REGEX: '${.RELEASE_CANDIDATE_TAG_REGEX}'"
    echo "test: ${releaseCandidateTag ==~ el.cicd.RELEASE_CANDIDATE_TAG_REGEX}"
    echo ''
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

    projectInfo.nfsShares.each { nfsShare ->
        validateNfsShare(projectInfo, nfsShare)
    }
}

def validateNfsShare(def projectInfo, def nfsShare) {
    assert nfsShare.envs : "missing nfsshare environments"
    nfsShare.envs.each { env ->
        assert projectInfo.nonProdEnvs.contains(env) || env == projectInfo.prodEnv
    }

    assert nfsShare.capacity ==~ /\d{1,4}(Mi|Gi)/ : "nfsShare.capacity missing or invalid format \\d{1,4}(Mi|Gi): '${nfsShare.capacity}'"
    assert nfsShare.accessMode ==~
        /(ReadWriteOnce|ReadWriteMany|ReadOnly)/ :
        "missing or invalid nfsShare.accessMode (ReadWriteOnce|ReadWriteMany|ReadOnly): '${nfsShare.accessMode}'"
    assert nfsShare.exportPath ==~ /\/([.\w-]+\/?)+/ : "missing or invalid nfsShare.nfsExportPath  /([.\\w-]+\\/?)+: '${nfsShare.nfsExportPath}'"
    assert nfsShare.server : "missing nfsShare.nfsServer"
    assert nfsShare.claimName: "missing nfsShare.claimName"
}

def cloneGitRepo(def module, def gitReference) {
   assert module ; assert gitReference

    dir (module.workDir) {
        checkout([$class: 'GitSCM',
                  branches: [[ name: gitReference ]],
                  userRemoteConfigs: [[ credentialsId: module.scmDeployKeyJenkinsId, url: module.repoUrl ]]
               ])

        def currentHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
        module.srcCommitHash = module.srcCommitHash ?: currentHash
        module.deploymentCommitHash = currentHash
    }
}

def createCloneRepoStages(def modules, Closure postCheckoutProcessing = null) {
    def cloneRepoStages = [:]
    modules.each { module ->
        cloneRepoStages["Checkout ${module.name}"] = {
            stage("Checkout ${module.name}") {
                def gitReference = module.gitReference ?: module.scmBranch
                cloneGitRepo(module, gitReference)
                
                if (postCheckoutProcessing) {
                    postCheckoutProcessing(module)
                }
            }
        }
    }
    
    return cloneRepoStages
}

def getNonProdDeploymentBranchName(def projectInfo, def component, def deploymentEnv) {
    return (projectInfo.testEnvs.contains(deploymentEnv) || deploymentEnv == el.cicd.preProdEnv)  ?
        "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${deploymentEnv}-${component.srcCommitHash}" : null
}