/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */


def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.releaseVersion = args.releaseVersion

    stage('Check version tag is valid SemVer') {
        loggingUtils.echoBanner("CHECK VERSION TAG ${projectInfo.releaseVersion} IS VALID SEMVER WIHOUT BUILD INFO")
        
        createReleaseCandidateUtils.verifyVersionTagValidSemver(projectInfo)
    }
        
    stage('Validate version tag unused') {
        loggingUtils.echoBanner("VERIFY THE TAG ${projectInfo.releaseVersion} DOES NOT EXIST IN ANY COMPONENT\'S GIT REPOSITORY")

        createReleaseCandidateUtils.verifyVersionTagDoesNotExistInScm(projectInfo)
        
        loggingUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN PRE-PROD IMAGE REGISTRY AS ${projectInfo.releaseVersion}")

        createReleaseCandidateUtils.verifyReleaseCandidateImagesDoNotExistInImageRegistry(projectInfo)
    }

    stage ('Select components in Release Candidate') {
        loggingUtils.echoBanner("SELECT COMPONENTS TO TAG AS RELEASE CANDIDATE ${projectInfo.releaseVersion}",
                                '',
                                "NOTE: Only components currently deployed in ${projectInfo.preProdNamespace} will be considered")

        createReleaseCandidateUtils.selectReleaseCandidateComponents(projectInfo, args)
    }

    stage('Confirm production manifest for release version') {
        createReleaseCandidateUtils.confirmReleaseCandidateManifest(projectInfo, args)
    }

    stage('Tag all images and Git repos') {
        loggingUtils.echoBanner("CREATING RELEASE CANDIDATE BY TAGGING IMAGES AND GIT REPOS")
                                
        createReleaseCandidateUtils.createReleaseCandidate(projectInfo)
    }
}