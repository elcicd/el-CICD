/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def gatherAllVersionGitTagsAndBranches(def projectInfo) {
    projectInfo.hasBeenReleased = false
    projectInfo.components.each { component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            def gitCmd = "git ls-remote ${component.repoUrl} '**/${projectInfo.releaseCandidateTag}-*' '**/${projectInfo.releaseVersionTag}-*'"
            def branchAndTagNames = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', gitCmd))

            if (branchAndTagNames) {
                branchAndTagNames = branchAndTagNames.split('\n')
                assert branchAndTagNames.size() < 3
                branchAndTagNames = branchAndTagNames.each { name ->
                    if (name.contains('/tags/')) {
                        component.releaseCandidateGitTag = name.trim().find( /[\w.-]+$/)
                        assert component.releaseCandidateGitTag ==~ "${projectInfo.releaseCandidateTag}-[\\w]{7}"
                        echo "--> FOUND RELEASE CANDIDATE TAG [${component.name}]: ${component.releaseCandidateGitTag}"
                    }
                    else {
                        projectInfo.hasBeenReleased = true
                        component.deploymentBranch = name.trim().find( /[\w.-]+$/)
                        assert component.deploymentBranch ==~ "${projectInfo.releaseVersionTag}-[\\w]{7}"
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

def updateProjectMetaInfo(def projectInfo) {
    stage('update project meta-info for production') {
        def componentNames = projectInfo.components.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(',')

        sh """
            ${loggingUtils.shellEchoBanner("UPDATE ${projectInfo.id}-${el.cicd.META_INFO_POSTFIX}")}

            oc delete --ignore-not-found cm ${projectInfo.id}-${el.cicd.META_INFO_POSTFIX} -n ${projectInfo.prodNamespace}

            ${shCmd.echo ''}
            oc create cm ${projectInfo.id}-${el.cicd.META_INFO_POSTFIX} \
                --from-literal=projectid=${projectInfo.id} \
                --from-literal=release-version=${projectInfo.releaseVersionTag} \
                --from-literal=release-region=${projectInfo.releaseRegion ?: el.cicd.UNDEFINED} \
                --from-literal=components=${componentNames} \
                --from-literal=build-number=${BUILD_NUMBER} \
                -n ${projectInfo.prodNamespace}

            ${shCmd.echo ''}
            oc label cm ${projectInfo.id}-${el.cicd.META_INFO_POSTFIX} \
                projectid=${projectInfo.id} \
                release-region=${projectInfo.releaseRegion ?: el.cicd.UNDEFINED} \
                release-version=${projectInfo.releaseVersionTag} \
                build-number=${BUILD_NUMBER} \
                -n ${projectInfo.prodNamespace}
        """
    }
}

def cleanupPreviousRelease(def projectInfo) {
    sh """
        ${loggingUtils.shellEchoBanner("REMOVING ALL RESOURCES FOR ${projectInfo.id} THAT ARE NOT PART OF ${projectInfo.releaseVersionTag}")}

        oc delete ${el.cicd.OKD_CLEANUP_RESOURCE_LIST} -l projectid=${projectInfo.id},release-version!=${projectInfo.releaseVersionTag} -n ${projectInfo.prodNamespace}
    """

    deploymentUtils.waitingForPodsToTerminate(projectInfo.prodNamespace)
}
