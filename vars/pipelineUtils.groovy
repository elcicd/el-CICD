/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
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

def getNonProdDeploymentBranchName(def projectInfo, def microService, def deploymentEnv) {
    return (projectInfo.testEnvs.contains(deploymentEnv) || deploymentEnv == el.cicd.preProdEnv)  ?
        "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${deploymentEnv}-${microService.srcCommitHash}" : null
}

def gatherProjectInfoStage(def projectId) {
    assert projectId

    def projectInfo
    stage('Gather project information') {
        echoBanner("GATHER PROJECT INFORMATION FOR ${projectId}")

        dir (el.cicd.PROJECT_DEFS_DIR) {
            def projectFile = findFiles(glob: "**/${projectId}.json")
            projectFile.addAll(findFiles(glob: "**/${projectId}.js")
            projectFile.addAll(findFiles(glob: "**/${projectId}.yml")
            projectFile.addAll(findFiles(glob: "**/${projectId}.yaml")
            
            // projectFile = findFiles(glob: "**/${projectId}.*")
            if (projectFile) {
                projectFile = projectFile[0].path
                try {
                    projectInfo= readYaml file: projectFile
                }
                catch (Exception e) {
                    projectInfo = readJSON file: projectFile
                }
            }
            else {
                errorBanner("PROJECT NOT FOUND: ${projectId}")
            }
        }

        projectInfo.id = projectId

        projectInfo.cicdMasterNamespace = "${projectInfo.rbacGroup}-${el.cicd.EL_CICD_GROUP_NAMESPACE_POSTFIX}"

        projectInfo.microServices.each { microService ->
            microService.projectId = projectInfo.id
            microService.name = microService.gitRepoName.toLowerCase().replaceAll(/[^-0-9a-z]/, '-')
            microService.id = "${projectInfo.id}-${microService.name}"

            microService.workDir = "${WORKSPACE}/${microService.gitRepoName}"

            microService.gitRepoUrl = "git@${projectInfo.scmHost}:${projectInfo.scmOrganization}/${microService.gitRepoName}.git"
            microService.gitSshPrivateKeyName = "${microService.id}-${el.cicd.GIT_CREDS_POSTFIX}"
        }

        projectInfo.devEnv = el.cicd.devEnv

        projectInfo.testEnvs = (el.cicd.testEnvs && projectInfo.enabledTestEnvs) ?
            el.cicd.testEnvs.findAll { projectInfo.enabledTestEnvs.contains(it) } : []

        projectInfo.preProdEnv = el.cicd.preProdEnv
        projectInfo.prodEnv = el.cicd.prodEnv

        projectInfo.nonProdEnvs = [projectInfo.devEnv]
        projectInfo.nonProdEnvs.addAll(projectInfo.testEnvs)
        projectInfo.nonProdEnvs.add(projectInfo.preProdEnv)

        def sandboxNamespacePrefix = "${projectInfo.id}-${el.cicd.SANDBOX_NAMESPACE_BADGE}"
        projectInfo.sandboxNamespaces = []
        (1..projectInfo.sandboxEnvs).each { i ->
            projectInfo.sandboxNamespaces += "${sandboxNamespacePrefix}-${i}"
        }

        projectInfo.DEV_ENV = el.cicd.DEV_ENV
        projectInfo.PRE_PROD_ENV = el.cicd.PRE_PROD_ENV
        projectInfo.PROD_ENV = el.cicd.PROD_ENV

        projectInfo.TEST_ENVS = projectInfo.testEnvs.collect { it.toUpperCase() }
        projectInfo.NON_PROD_ENVS = [projectInfo.DEV_ENV]
        projectInfo.NON_PROD_ENVS.addAll(projectInfo.TEST_ENVS)
        projectInfo.NON_PROD_ENVS.add(projectInfo.PRE_PROD_ENV)

        if (projectInfo.devEnv) {
            projectInfo.nonProdNamespaces = [(projectInfo.devEnv): "${projectInfo.id}-${projectInfo.devEnv}"]
            projectInfo.testEnvs.each { env ->
                projectInfo.nonProdNamespaces[env] = "${projectInfo.id}-${env}"
            }
            projectInfo.nonProdNamespaces[(projectInfo.preProdEnv)] = "${projectInfo.id}-${projectInfo.preProdEnv}"
        }

        projectInfo.devNamespace = projectInfo.devEnv ? projectInfo.nonProdNamespaces[projectInfo.devEnv] : null
        projectInfo.preProdNamespace = projectInfo.nonProdNamespaces[projectInfo.preProdEnv]
        projectInfo.prodNamespace = projectInfo.prodEnv ? "${projectInfo.id}-${projectInfo.prodEnv}" : null
    }

    return projectInfo
}

def spacedEcho(def msg) {
    echo "\n${msg}\n"
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
    if (msgs[0] instanceof List) {
        msgs = msgs[0]
    }
    
    return """

        ===========================================

        ${msgs.join("\n        ")}

        ===========================================

    """
}
