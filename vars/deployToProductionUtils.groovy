/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def gatherAllVersionGitTagsAndBranches(def projectInfo) {
    assert projectInfo.releaseCandidateTag =~ /[\w][\w.-]*/: "projectInfo.releaseCandidateTag must match the pattern [\\w][\\w.-]*: ${projectInfo.releaseCandidateTag}"
    assert projectInfo.releaseVersionTag =~ /[\w][\w.-]*/: "projectInfo.releaseVersionTag must match the pattern [\\w][\\w.-]*: ${projectInfo.releaseVersionTag}"

    projectInfo.hasBeenReleased = false
    projectInfo.microServices.each { microService ->
        withCredentials([sshUserPrivateKey(credentialsId: microService.gitSshPrivateKeyName, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            def gitCmd = "git ls-remote ${microService.gitRepoUrl} '**/${projectInfo.releaseCandidateTag}-*' '**/${projectInfo.releaseVersionTag}-*'"
            def branchAndTagNames = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', gitCmd))

            if (branchAndTagNames) {
                branchAndTagNames = branchAndTagNames.split('\n')
                assert branchAndTagNames.size() < 3
                branchAndTagNames = branchAndTagNames.each { name ->
                    if (name.contains('/tags/')) {
                        microService.releaseCandidateGitTag = name.trim().find( /[\w.-]+$/)
                        assert microService.releaseCandidateGitTag ==~ "${projectInfo.releaseCandidateTag}-[\\w]{7}"
                        echo "--> FOUND RELEASE CANDIDATE TAG [${microService.name}]: ${microService.releaseCandidateGitTag}"
                    }
                    else {
                        projectInfo.hasBeenReleased = true
                        microService.deploymentBranch = name.trim().find( /[\w.-]+$/)
                        assert microService.deploymentBranch ==~ "${projectInfo.releaseVersionTag}-[\\w]{7}"
                        echo "--> FOUND RELEASE VERSION DEPLOYMENT BRANCH [${microService.name}]: ${microService.deploymentBranch}"
                    }
                }
                microService.srcCommitHash = branchAndTagNames[0].find(/[\w]{7}+$/)
            }
            else {
                echo "--> NO RELEASE CANDIDATE OR VERSION INFORMATION FOUND FOR: ${microService.name}"
            }
        }
    }
}

def updateProjectMetaInfo(def projectInfo) {
    assert projectInfo.id ; assert projectInfo.releaseVersionTag =~ /[\w][\w.-]*/

    stage('update project meta-info for production') {
        def microServiceNames = projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(',')

        sh """
            ${loggingUtils.shellEchoBanner("UPDATE ${projectInfo.id}-${el.cicd.CM_META_INFO_POSTFIX}")}

            oc delete --ignore-not-found cm ${projectInfo.id}-${el.cicd.CM_META_INFO_POSTFIX} -n ${projectInfo.prodNamespace}

            ${shCmd.echo ''}
            oc create cm ${projectInfo.id}-${el.cicd.CM_META_INFO_POSTFIX} \
                --from-literal=projectid=${projectInfo.id} \
                --from-literal=release-version=${projectInfo.releaseVersionTag} \
                --from-literal=release-region=${projectInfo.releaseRegion ?: el.cicd.UNDEFINED} \
                --from-literal=microservices=${microServiceNames} \
                --from-literal=build-number=${BUILD_NUMBER} \
                -n ${projectInfo.prodNamespace}

            ${shCmd.echo ''}
            oc label cm ${projectInfo.id}-${el.cicd.CM_META_INFO_POSTFIX} \
                projectid=${projectInfo.id} \
                release-region=${projectInfo.releaseRegion ?: el.cicd.UNDEFINED} \
                release-version=${projectInfo.releaseVersionTag} \
                build-number=${BUILD_NUMBER} \
                -n ${projectInfo.prodNamespace}
        """
    }
}

def cleanupPreviousRelease(def projectInfo) {
    assert projectInfo.id ; assert projectInfo.releaseVersionTag  =~ /[\w][\w.-]*/

    sh """
        ${loggingUtils.shellEchoBanner("REMOVING ALL RESOURCES FOR ${projectInfo.id} THAT ARE NOT PART OF ${projectInfo.releaseVersionTag}")}

        oc delete ${el.cicd.OKD_CLEANUP_RESOURCE_LIST} -l projectid=${projectInfo.id},release-version!=${projectInfo.releaseVersionTag} -n ${projectInfo.prodNamespace}
    """

    deploymentUtils.waitingForPodsToTerminate(projectInfo.prodNamespace)
}
