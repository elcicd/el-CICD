/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * General pipeline utilities
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def cloneGitRepo(def component, def gitReference) {
   assert component ; assert gitReference

    dir (component.workDir) {
        checkout([$class: 'GitSCM',
                  branches: [[ name: gitReference ]],
                  userRemoteConfigs: [[ credentialsId: component.gitSshPrivateKeyName, url: component.gitRepoUrl ]]
               ])

        def currentHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
        component.srcCommitHash = component.srcCommitHash ?: currentHash
        component.deploymentCommitHash = currentHash
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
            projectFile = projectFile ?: findFiles(glob: "**/${projectId}.yml")
            projectFile = projectFile ?: findFiles(glob: "**/${projectId}.yaml")

            if (projectFile) {
                projectFile = projectFile[0].path
                try {
                    projectInfo = readYaml file: projectFile
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

        projectInfo.cicdMasterNamespace = "${projectInfo.rbacGroup}-${el.cicd.CICD_MASTER_NAMESPACE_POSTFIX}"

        projectInfo.components = []
        projectInfo.components.addAll(projectInfo.microServices)
        projectInfo.components.addAll(projectInfo.libraries)

        projectInfo.components.each { component ->
            component.projectId = projectInfo.id
            component.name = component.gitRepoName.toLowerCase().replaceAll(/[^-0-9a-z]/, '-')
            component.id = "${projectInfo.id}-${component.name}"

            component.workDir = "${WORKSPACE}/${component.gitRepoName}"

            component.gitRepoUrl = "git@${projectInfo.scmHost}:${projectInfo.scmOrganization}/${component.gitRepoName}.git"
            component.gitSshPrivateKeyName = "${component.id}-${el.cicd.GIT_CREDS_POSTFIX}"
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
        projectInfo.sandboxEnvs = projectInfo.sandboxEnvs ?: 0
        if (projectInfo.sandboxEnvs >= 1) {
            (1..projectInfo.sandboxEnvs).each { i ->
                projectInfo.sandboxNamespaces += "${sandboxNamespacePrefix}-${i}"
            }
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


        projectInfo.resourceQuotas = projectInfo.resourceQuotas ?: [:]
        projectInfo.nfsShares = projectInfo.nfsShares ?: []
    }

    validateProjectInfo(projectInfo)

    return projectInfo
}

def validateProjectInfo(def projectInfo) {
    assert projectInfo.rbacGroup : 'missing rbacGroup'

    assert projectInfo.scmHost ==~
        /^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\-]*[A-Za-z0-9])$/ :
        "bad or missing scmHost '${projectInfo.scmHost}"
    assert projectInfo.scmRestApiHost ==~
        /^(([\p{Alnum}]|[\p{Alnum}][\p{Alnum}-]*[\p{Alnum}])\.)*([\p{Alnum}]|[\p{Alnum}][\p{Alnum}-]*[\p{Alnum}])(\/[\p{Alnum}][\p{Alnum}-]*)*$/ :
        "bad or missing scmRestApiHost '${projectInfo.scmHost}"
    assert projectInfo.scmOrganization : "missing scmOrganization"
    assert projectInfo.gitBranch : "missing git branch"
    assert projectInfo.sandboxEnvs ==~ /\d{0,2}/ : "sandboxEnvs must be an integer >= 0"
    assert projectInfo.components.size() > 0 : "No microservices or libraries defined"

    projectInfo.components.each { component ->
        assert component.gitRepoName ==~ /[\w-.]+/ : "bad git repo name for microservice, [\\w-.]+: ${component.gitRepoName}"
        assert component.codeBase ==~ /[a-z-]+/ : "bad git repo name for codeBase, [a-z]+: ${component.codeBase}"
    }

    projectInfo.enabledTestEnvs.each { env ->
        assert el.cicd.testEnvs.contains(env) : "test environment '${env}' must be in [${el.cicd.testEnvs}]"
    }

    projectInfo.resourceQuotas.each { env, resourceQuotaFile ->
        assert projectInfo.nonProdEnvs.contains(env) || env == projectInfo.prodEnv ||  env == 'default' :
            "resourceQuotas keys must be either an environment or 'default': '${env}'"

        assert resourceQuotaFile && fileExists("${el.cicd.RESOURCE_QUOTA_DIR}/${resourceQuotaFile}")
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

def spacedEcho(def msg) {
    echo "\n${msg}\n"
}

def echoBanner(def ... msgs) {
    echo createBanner(msgs)
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
