/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def projectInfo.versionTag = args.versionTag

    stage ('Checkout all project components') {
        promoteReleaseCandidateUtils.runCloneReleaseCandidateGitReposStages(projectInfo)
    }

    stage ('Verify release candidate exists and has not been promoted') {
        // promoteReleaseCandidateUtils.verifyProductionTagDoesNotExist(projectInfo)
        
        // promoteReleaseCandidateUtils.verifyReleaseCandidateTag(projectInfo)
    }
    
    stage ('Gather components to be promoted') {
    }

    stage('Confirm production manifest for release version') {
    }

    stage ('Create umbrella prod chart and commit changes') {
    }

    stage ('Promote images and push umbrella prod chart') {
    }

}