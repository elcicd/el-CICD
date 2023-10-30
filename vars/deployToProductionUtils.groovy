/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def gatherAllVersionGitTagsAndBranches(def projectInfo) {
    projectInfo.hasBeenReleased = false
    projectInfo.components.each { component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            def gitCmd = "git ls-remote ${component.scmRepoUrl} '**/${projectInfo.versionTag}-*'"
            def branchAndTagNames = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', gitCmd))

            if (branchAndTagNames) {
                branchAndTagNames = branchAndTagNames.split('\n')
                assert branchAndTagNames.size() < 3
                branchAndTagNames = branchAndTagNames.each { name ->
                    if (name.contains('/tags/')) {
                        component.releaseCandidateScmTag = name.trim().find( /[\w.-]+$/)
                        assert component.releaseCandidateScmTag ==~ "${projectInfo.versionTag}-[\\w]{7}"
                        echo "--> FOUND RELEASE CANDIDATE TAG [${component.name}]: ${component.releaseCandidateScmTag}"
                    }
                    else {
                        projectInfo.hasBeenReleased = true
                        component.deploymentBranch = name.trim().find( /[\w.-]+$/)
                        assert component.deploymentBranch ==~ "${projectInfo.versionTag}-[\\w]{7}"
                        echo "--> FOUND RELEASE VERSION DEPLOYMENT BRANCH [${component.name}]: ${component.deploymentBranch}"
                    }
                }
                component.srcCommitHash = branchAndTagNames[0].find(/[\w]{7}+$/)
            }
            else {
                echo "--> NO RELEASE CANDIDATE OR VERSION INFORMATION FOUND FOR: ${component.name}"
            }
        }
    }
}

def cleanupPreviousRelease(def projectInfo) {
    sh """
        ${loggingUtils.shellEchoBanner("REMOVING ALL RESOURCES FOR ${projectInfo.id} THAT ARE NOT PART OF ${projectInfo.versionTag}")}

        oc delete ${el.cicd.OKD_CLEANUP_RESOURCE_LIST} -l el-cicd.io/projectid=${projectInfo.id},release-version!=${projectInfo.versionTag} -n ${projectInfo.prodNamespace}
    """

    deploymentUtils.waitingForPodsToTerminate(projectInfo.prodNamespace)
}
