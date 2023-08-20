/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def projectInfo.versionTag = args.versionTag

    stage ('Validate release Candidate') {
        promoteProjectToProdUtils.gatherReleaseCandidateInfo(projectInfo)
        
        promoteProjectToProdUtils.verifyReleaseCandidate(projectInfo)
    }

    stage('Summarize and confirm promotion') {
        promoteProjectToProdUtils.confirmPromotion(projectInfo)
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