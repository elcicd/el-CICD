/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-release-candidate-template
 *
 */
 
import 

@Field
SEMVER_REGEX = /^((([0-9]+)\.([0-9]+)\.([0-9]+)(?:-([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?)(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?)$/

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.versionTag = args.versionTag
    
    if (args.strictSemver && !SEMVER_REGEX.matches(projectInfo.versionTag) {
        loggingUtils.errorBanner('STRICT SEMVER VALIDATION IS ENABLED',
                                 '',
                                 "${projectInfo.versionTag} in NOT a valid SemVer",
                                 '',
                                 'Disable strict SemVer validation or see https://semver.org/ for more information')
    }

    stage('Verify version tag does not exist in SCM') {
        loggingUtils.echoBanner("VERIFY THE TAG ${projectInfo.versionTag} DOES NOT EXIST IN ANY COMPONENT\'S SCM REPOSITORY")

        createReleaseCandidateUtils.verifyVersionTagDoesNotExistInScm(projectInfo)
    }

    stage('Verify version tag does NOT exist in pre-prod image registry') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN PRE-PROD IMAGE REGISTRY AS ${projectInfo.versionTag}")

        createReleaseCandidateUtils.verifyReleaseCandidateImagesDoNotExistInImageRegistry(projectInfo)
    }

    stage ('Select components in Release Candidate') {
        loggingUtils.echoBanner("SELECT COMPONENTS TO TAG AS RELEASE CANDIDATE ${projectInfo.versionTag}",
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