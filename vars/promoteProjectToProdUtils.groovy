/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.releaseCandidateComps = projectInfo.components.findAll{ component ->
        versionTagScript = /git ls-remote --tags ${component.scmRepoUrl} | grep "${projectInfo.versionTag}-[a-z0-9]\{7\}"/
        component.releaseCandidateScmTag = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript))
        
        return component.releaseCandidateScmTag
    }

    if (!projectInfo.releaseCandidateComps) {
        def msg = "NO COMPONENTS HAVE BEEN TAGGED AS RELEASE CANDIDATE ${projectInfo.versionTag} IN SCM"
        loggingUtils.errorBanner(msg)
    }

}

def confirmPromotion(def projectInfo, def args) {
    def msg = loggingUtils.echoBanner(
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

def checkoutReleaseCandidateRepos(def projectInfo) {
    def modules = [].addAll(projectInfo.releaseCandidateComps)
    modules.add(projectInfo.projectModule)
    concurrentUtils.runCloneGitReposStages(projectInfo, modules) { module ->
        sh "git checkout ${component.releaseCandidateScmTag}"
    }
}