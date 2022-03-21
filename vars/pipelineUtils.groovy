/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * General pipeline utilities
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

        projectInfo.microServices = projectInfo.microServices ?: []
        projectInfo.libraries = projectInfo.libraries ?: []

        validateProjectInfoFile(projectInfo)

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
            component.testWorkDir = component.systemTests ? "${WORKSPACE}/${component.systemTests.gitRepoName}" : null

            component.gitRepoUrl = "git@${projectInfo.scmHost}:${projectInfo.scmOrganization}/${component.gitRepoName}.git"
            component.gitSshPrivateKeyName = "${component.id}-${el.cicd.GIT_CREDS_POSTFIX}"
        }

        projectInfo.devEnv = el.cicd.devEnv

        projectInfo.hotfixEnv = el.cicd.hotfixEnv

        projectInfo.testEnvs = (el.cicd.testEnvs && projectInfo.enabledTestEnvs) ?
            el.cicd.testEnvs.findAll { projectInfo.enabledTestEnvs.contains(it) } : []

        projectInfo.preProdEnv = el.cicd.preProdEnv
        projectInfo.prodEnv = el.cicd.prodEnv

        projectInfo.nonProdEnvs = [projectInfo.devEnv]
        projectInfo.allowsHotfixes && projectInfo.nonProdEnvs << projectInfo.hotfixEnv
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
            projectInfo.nonProdNamespaces[(projectInfo.hotfixEnv)] = projectInfo.hotfixNamespace
        }

        projectInfo.testEnvs.each { env ->
            projectInfo.nonProdNamespaces[(env)] = "${projectInfo.id}-${env}"
        }
        projectInfo.nonProdNamespaces[(projectInfo.preProdEnv)] = projectInfo.preProdNamespace
        def sandboxes = projectInfo.sandboxEnvs ?: 0
        projectInfo.sandboxEnvs = []
        projectInfo.sandboxNamespaces = []
        if (sandboxes) {
            (1..sandboxEnvs).each { i ->
                projectInfo.sandboxEnvs << "${el.cicd.SANDBOX_NAMESPACE_BADGE}-${i}"
                projectInfo.sandboxNamespaces << "${projectInfo.id}-${projectInfo.sandboxEnvs[i]}"
            }
        }

        projectInfo.builderNamespaces = [projectInfo.devNamespace]
        projectInfo.allowsHotfixes && projectInfo.builderNamespaces << projectInfo.hotfixNamespace
        projectInfo.builderNamespaces.addAll(projectInfo.sandboxNamespaces)

        projectInfo.releaseRegions = projectInfo.releaseRegions ?: []

        projectInfo.resourceQuotas = projectInfo.resourceQuotas ?: [:]
        projectInfo.nfsShares = projectInfo.nfsShares ?: []
    }

    return projectInfo
}

def validateProjectInfoFile(def projectInfo) {
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
    assert (projectInfo.microServices.size() > 0 || projectInfo.libraries > 0) : "No microservices or libraries defined"

    projectInfo.microServices.each { component ->
        assert component.gitRepoName ==~ /[\w-.]+/ : "bad git repo name for microservice, [\\w-.]+: ${component.gitRepoName}"
        assert component.codeBase ==~ /[a-z-]+/ : "bad codeBase name, [a-z-]+: ${component.codeBase}"
    }

    projectInfo.libraries.each { component ->
        assert component.gitRepoName ==~ /[\w-.]+/ : "bad git repo name for microservice, [\\w-.]+: ${component.gitRepoName}"
        assert component.codeBase ==~ /[a-z-]+/ : "bad codeBase name, [a-z-]+: ${component.codeBase}"
    }

    projectInfo.enabledTestEnvs.each { env ->
        assert el.cicd.testEnvs.contains(env) : "test environment '${env}' must be in [${el.cicd.testEnvs}]"
    }

    def badReleaseRegions = projectInfo.releaseRegions.findAll { !(it ==~ /[a-z-]+/) }
    assert !badReleaseRegions : "bad release region name(s) ${badReleaseRegions}, [a-z-]+, ${projectInfo.releaseRegions}"

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
    return """
        ===========================================

        ${msgFlatten(null, msgs).join("\n        ")}

        ===========================================
    """
}

def msgFlatten(def list, def msgs) {
    list = list ?: []
    if (!(msgs instanceof String) && !(msgs instanceof GString)) {
        msgs.each { msg ->
            list = msgFlatten(list, msg)
        }
    }
    else {
        list += msgs
    }

    return  list
}
