/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

void call(Map args) {    
    if (!args.releaseVersion) {
        loggingUtils.errorBanner("RELEASE VERSION NOT ENTERED")
    }
    
    def projectInfo = args.projectInfo
    projectInfo.releaseVersion = args.releaseVersion
    projectInfo.deployToEnv = projectInfo.prodEnv

    stage ('Validate release Candidate') {
        loggingUtils.echoBanner("VALIDATING ${projectInfo.id} RELEASE CANDIDATE ${projectInfo.releaseVersion}")
        
        promoteProjectToProdUtils.verifyProjectReleaseVersion(projectInfo)

        promoteProjectToProdUtils.gatherReleaseCandidateRepos(projectInfo)
        
    }
    stage ('Checkout release component repos') {
        loggingUtils.echoBanner("CLONE PROJECT AND RELEASE CANDIDATE COMPONENT REPOS")

        promoteProjectToProdUtils.checkoutReleaseCandidateRepos(projectInfo)
    }

    stage ('Create release') {
        loggingUtils.echoBanner("CREATE RELEASE REPO")

        promoteProjectToProdUtils.createReleaseVersionComponentSubCharts(projectInfo)

        promoteProjectToProdUtils.createReleaseVersionUmbrellaChart(projectInfo)
    }

    stage('Summarize and confirm promotion') {
        promoteProjectToProdUtils.confirmPromotion(projectInfo, args)
    }

    stage ('Promote release version') {
        loggingUtils.echoBanner("PROMOTE RELEASE CANDIDATE TO PROD")

        promoteProjectToProdUtils.pushReleaseVersion(projectInfo)
        
        promoteProjectToProdUtils.runPromoteImagesStages(projectInfo)
    }
}