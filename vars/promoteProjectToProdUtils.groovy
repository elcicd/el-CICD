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