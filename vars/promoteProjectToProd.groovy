/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

void call(Map args) {    
    if (!args.versionTag) {
        loggingUtils.errorBanner("VERSION TAG NOT ENTERED")
    }
    
    def projectInfo = args.projectInfo
    projectInfo.versionTag = args.versionTag

    stage ('Validate release Candidate') {
        loggingUtils.echoBanner("VALIDATING ${projectInfo.id} RELEASE CANDIDATE ${projectInfo.versionTag}")

        promoteProjectToProdUtils.gatherReleaseCandidateRepos(projectInfo)
        
        if (!projectInfo.releaseCandidateComps) {
            loggingUtils.errorBanner("RELEASE CANDIDATE ${projectInfo.versionTag} NOT FOUND IN SCM")
        }
    }

    stage('Summarize and confirm promotion') {
        promoteProjectToProdUtils.confirmPromotion(projectInfo, args)
    }

    stage ('Checkout release component repos') {
        loggingUtils.echoBanner("CLONE PROJECT AND RELEASE CANDIDATE COMPONENT REPOS")

        promoteProjectToProdUtils.checkoutReleaseCandidateRepos(projectInfo)
    }

    stage ('Create release') {
        loggingUtils.echoBanner("CREATE RELEASE REPO")

        promoteProjectToProdUtils.createReleaseRepo(projectInfo)        
    }

    stage ('Commit and push release') {
        loggingUtils.echoBanner("COMMIT AND PUSH RELEASE REPO")

        promoteProjectToProdUtils.commitRelease(projectInfo)
    }


    stage ('Promote release candidate images to prod') {
        loggingUtils.echoBanner("PROMOTE RELEASE CANDIDATE IMAGES TO PROD")

        promoteProjectToProdUtils.promoteReleaseCandidateImages(projectInfo)
    }

}