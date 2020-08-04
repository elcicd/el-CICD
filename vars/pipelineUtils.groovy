/*
 * General pipeline utilities
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def cloneGitRepo(microService, gitReference) {
   assert microService ; assert gitReference

    dir (microService.workDir) {
        checkout([$class: 'GitSCM',
                  branches: [[ name: gitReference ]],
                  userRemoteConfigs: [[ credentialsId: microService.gitSshPrivateKeyName, url: microService.gitRepoUrl ]]
               ])

        def currentHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
        microService.srcCommitHash = microService.srcCommitHash ?: currentHash
        microService.deploymentCommitHash = currentHash
    }
}

/*
 * NOTE: Deployment branches don't exist for the dev (build) environment, because it's deployment is matched with
 *       matched to HEAD of the development (build) branch; that is, it's deployment is always in sync with
 *       the source.
 */
def assignDeploymentBranchName(def projectInfo, def microService, def deploymentEnv) {
    assert projectInfo: "pipelineUtils.assignDeploymentBranchName: projectInfo cannot be null"
    assert microService: "pipelineUtils.assignDeploymentBranchName: deploymentEnv cannot be null"
    assert deploymentEnv: "pipelineUtils.assignDeploymentBranchName: deploymentEnv cannot be null"
    assert projectInfo.testEnvs.contains(deploymentEnv): "pipelineUtils.assignDeploymentBranchName: deploymentEnv must be a test env ${projectInfo.testEnvs}: ${deploymentEnv}"

    microService.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${deploymentEnv}-${microService.srcCommitHash}"

    def previousDeploymentEnv
    projectInfo.testEnvs.find {
        def found = (it == deploymentEnv)
        previousDeploymentEnv = !found ? it : previousDeploymentEnv
        return found
    }

    if (previousDeploymentEnv != null && previousDeploymentEnv != projectInfo.devEnv) {
        microService.previousDeploymentBranchName = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${previousDeploymentEnv}-${microService.srcCommitHash}"
    }
}

def gatherProjectInfoStage(def projectId) {
    assert projectId

    def projectInfo
    stage('Gather project information') {
        echoBanner("GATHER PROJECT INFORMATION FOR ${projectId}")

        dir ("${WORKSPACE}/el-CICD-project-repository") {
            git url: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY,
                branch: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID

            projectFile = findFiles(glob: "**/${projectId}.*")[0].path
            try {
                projectInfo = readYaml file: projectFile
            }
            catch (Exception e) {
                projectInfo = readJSON file: projectFile
            }
        }

        projectInfo.id = projectId

        projectInfo.nonProdCicdNamespace = "${projectInfo.rbacGroup}-${el.cicd.CICD_NON_PROD}"
        projectInfo.prodCicdNamespace = "${projectInfo.rbacGroup}-${el.cicd.CICD_PROD}"

        projectInfo.microServices.each { microService ->
            microService.projectId = projectInfo.id
            microService.name = microService.gitRepoName.toLowerCase().replaceAll(/[^-0-9a-z]/, '-')
            microService.id = "${projectInfo.id}-${microService.name}"

            microService.workDir = "${WORKSPACE}/${microService.gitRepoName}"

            microService.gitRepoUrl = "git@${projectInfo.scmHost}:${projectInfo.scmOrganization}/${microService.gitRepoName}.git"
            microService.gitSshPrivateKeyName = "${microService.id}-${el.cicd.GIT_CREDS_POSTFIX}"
        }

        projectInfo.devEnv = el.cicd.devEnv
        projectInfo.prodEnv = el.cicd.prodEnv

        projectInfo.testEnvs = el.cicd.testEnvs.findAll { !projectInfo.disabledTestEnvs.contains(it) }
        if(!projectInfo.testEnvs) {
            errorBanner('There must be at least one test environment per project', "Project id: ${projectInfo.id}, DISABLED ENVS: ${projectInfo.disabledTestEnvs.join(', ')}")
        }
        projectInfo.nonProdEnvs = [projectInfo.devEnv]
        projectInfo.nonProdEnvs.addAll(projectInfo.testEnvs)

        projectInfo.preProdEnv = projectInfo.testEnvs.last()
        
        def sandboxNamespacePrefix = "${projectInfo.id}-${el.cicd.SANDBOX_NAMESPACE_BADGE}"
        projectInfo.sandboxNamespaces = []        
        (1..projectInfo.sandboxEnvs).each { i ->
            projectInfo.sandboxNamespaces += "${sandboxNamespacePrefix}-${i}"
        }

        projectInfo.DEV_ENV = el.cicd.DEV_ENV
        projectInfo.PROD_ENV = el.cicd.PROD_ENV

        projectInfo.TEST_ENVS = projectInfo.testEnvs.collect { it.toUpperCase() }
        projectInfo.NON_PROD_ENVS = [projectInfo.DEV_ENV]
        projectInfo.NON_PROD_ENVS.addAll(projectInfo.TEST_ENVS)
        projectInfo.PRE_PROD_ENV = projectInfo.NON_PROD_ENVS.last()

        projectInfo.nonProdNamespaces = [(projectInfo.devEnv): "${projectInfo.id}-${projectInfo.devEnv}"]
        projectInfo.testEnvs.each { env ->
            projectInfo.nonProdNamespaces[env] = "${projectInfo.id}-${env}".toString()
        }

        projectInfo.devNamespace = projectInfo.nonProdNamespaces[projectInfo.devEnv]
        projectInfo.preProdNamespace = projectInfo.nonProdNamespaces[projectInfo.preProdEnv]
        projectInfo.prodNamespace = "${projectInfo.id}-${projectInfo.prodEnv}"
    }

    return projectInfo
}

def echoBanner(def ... msgs) {
    echo createBanner(msgs)
}

def shellEcho(def msg) {
    return "{ echo '${msg}'; } 2> /dev/null"
}

def shellEchoBanner(def ... msgs) {
    return "{ echo '${createBanner(msgs)}'; } 2> /dev/null"
}

def errorBanner(def ... msgs) {
    error(createBanner(msgs))
}

def createBanner(def ... msgs) {
    return """

        ===========================================

        ${msgs.join("\n        ")}

        ===========================================

    """
}
