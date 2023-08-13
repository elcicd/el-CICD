/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def checkoutAllRepos(def projectInfo) {
    concurrentUtils.runCloneReleaseCandidateGitReposStages(projectInfo, projectInfo.components) { component ->
        dir(component.workDir) {
            if (sh(returnStdout: true,
                script: "git ls-remote ${component.scmRepo} --tags ${versonTag}")
        {
            component.promote = true
            sh "git checkout ${versionTag}"
        }
    }
    
    if (!projectInfo.components.find { it.promote }) {
        def msg = "RELEASE CANDIDATE DOES NOT EXIST IN SCM: ${projectInfo.versionTag}"
        loggingUtils.errorBanner(msg)
    }
}

def confirmPromotion(def projectInfo, def args) {
    def promotionComps = projectInfo.components.findAll{ it.promote }.collect { it.name }.join(', ')
    def skippedComps = projectInfo.components.findAll{ !it.promote }
    
    def msg = loggingUtils.echoBanner(
        "CONFIRM CREATION OF COMPONENT MANIFEST FOR RELEASE CANDIDATE VERSION ${projectInfo.versionTag}",
        '',
        '===========================================',
        '',
        '-> SELECTED COMPONENTS IN THIS VERSION WILL HAVE THEIR',
        "   - ${projectInfo.preProdEnv} IMAGES TAGGED AS ${projectInfo.versionTag} IN THE PRE-PROD IMAGE REGISTRY",
        "   - DEPLOYMENT BRANCHES [deployment-${projectInfo.preProdEnv}-<src-commit-has>] TAGGED AS ${projectInfo.versionTag}-<src-commit-hash>:",
        '',
        promotionNames,
        '',
        '---',
        '',
        '-> IGNORED COMPONENTS IN THIS VERSION:',
        '   - Will NOT be deployed in prod',
        '   - WILL BE REMOVED FROM prod if currently deployed and this version is promoted',
        '',
        removalNames,
        '',
        '===========================================',
        '',
        "WARNING: A Release Candidate CAN ONLY BE CREATED ONCE with version ${projectInfo.versionTag}",
        '',
        'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
        '',
        "Should Release Candidate ${projectInfo.versionTag} be created?"
    )
    
    jenkinsUtils.displayInputWithTimeout(msg, args)
}