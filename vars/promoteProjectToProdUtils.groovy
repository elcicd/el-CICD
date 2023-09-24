/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.releaseCandidateComps = projectInfo.components.findAll{ component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            versionTagScript = /git ls-remote --tags ${component.scmRepoUrl} '${projectInfo.versionTag}-[a-z0-9]\{7\}'/
            scmRepoTag = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript))

            if (scmRepoTag) {
                echo "-> RELEASE ${projectInfo.verstionTag} COMPONENT FOUND: ${component.scmRepoName}"
                component.releaseCandidateGitTag = scmRepoTag.subString(scmRepoTag.lastIndexOf('/'))
                assert component.releaseCandidateGitTag ==~ "${projectInfo.versionTag}-[\\w]{7}"
            }
            else {
                echo "-> Release ${projectInfo.verstionTag} component NOT found: ${component.scmRepoName}"
            }

            return component.releaseCandidatcmTag
        }
    }
}

def confirmPromotion(def projectInfo, def args) {
    def msg = loggingUtils.createBanner(
        "CONFIRM CREATION OF RELEASE ${projectInfo.versionTag}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> ACTIONS TO BE TAKEN:',
        "   - A DEPLOYMENT BRANCH [${projectInfo.versionTag}] WILL BE CREATED IN THE SCM REPO ${projectInfo.projectModule.scmRepoName}",
        "   - IMAGES TAGGED AS ${projectInfo.versionTag} WILL BE PUSHED TO THE PROD IMAGE REGISTRY",
        '   - COMPONENTS NOT IN THIS RELEASE WILL BE REMOVED FROM ${projectInfo.prodEnv}',
        '',
        '-> COMPONENTS IN RELEASE:',
        projectInfo.releaseCandidateComps.collect { it.name },
        '',
        '-> COMPONENTS NOT IN THIS RELEASE:',
        projectInfo.components.findAll{ !it.promote }.collect { it.name },
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        "WARNING: A Release Candidate CAN ONLY BE PROMOTED ONCE",
        '',
        'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
        '',
        "Should Release ${projectInfo.versionTag} be created?"
    )

    jenkinsUtils.displayInputWithTimeout(msg, args)
}

def checkoutAllRepos(def projectInfo) {
    def modules = [].addAll(projectInfo.releaseCandidateComps)
    modules.add(projectInfo.projectModule)
    concurrentUtils.runCloneGitReposStages(projectInfo, modules) { module ->
        sh "git checkout -B ${module.releaseCandidateScmTag}"
    }

    projectInfoUtils.cloneGitRepo(projectInfo.projectModule)
}

def createReleaseRepo(def projectInfo) {
    projectInfo.releaseCandidateComps.each { component ->
        compDeployWorkDir = "${projectInfo.module.workDir}/${component.scmRepoName}"
        sh"""
            git checkout -B ${projectInfo.versionTag}
            mkdir ${compDeployWorkDir}
            cp -R ${component.workDir}/.deploy ${compDeployWorkDir}
        """
    }
}