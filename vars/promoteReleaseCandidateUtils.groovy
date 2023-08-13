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
        loggingUtils.errorBanner("msg", msg)
    }
}

def confirmPromotion(def projectInfo, def args) {
    def promotionComps = projectInfo.components.findAll{ it.promote }.collect { it.name }.join(', ')
    def skippedComps = projectInfo.components.findAll{ !it.promote }
    
    def msg = pipelineUtils.createBanner(
        "CONFIRM THE RELEASE MANIFEST FOR VERSION ${projectInfo.versionTag}:",
        '',
        '===========================================',
        '',
        "-> Release Version Tag: ${projectInfo.releaseCandidateTag}",
        '',
        'Components in Release to be PROMOTED:',
        promotionComps,
        '',
        'Components NOT in Release:',
        skippedComps ? skippedComps.collect { it.name }.join(', ') : 'None',
        '===========================================',
        '',
        'PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION',
        '',
        "Should Release Candidate version ${projectInfo.releaseCandidateTag} be promoted?"
    )
    
    jenkinsUtils.displayInputWithTimeout(msg, args)
}