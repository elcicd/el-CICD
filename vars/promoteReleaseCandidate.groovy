/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def projectInfo.versionTag = args.versionTag

    stage ('Validate release Candidate') {
        promoteReleaseCandidateUtils.gatherReleaseCandidateInfo(projectInfo)
        
        promoteReleaseCandidateUtils.verifyReleaseCandidate(projectInfo)
    }

    stage('Summarize and confirm promotion') {
        promoteReleaseCandidateUtils.confirmPromotion(projectInfo)
    } 

    stage ('Checkout release component repos') {
    }

    stage ('Create release') {
    }
    
    stage ('Commit and push release') {
    }

    stage('Promote component images') {
    }

}