/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
 
 def verifyVersionTagDoesNotExistInScm(def projectInfo) {
    loggingUtils.echoBanner("VERIFY THE TAG ${projectInfo.versionTag} DOES NOT EXIST IN ANY COMPONENT\'S SCM REPOSITORY")

    projectInfo.components.each { component ->
        dir(component.workDir) {
            def tagExists = sh(returnStdout: true, script: "git ls-remote ${component.scmRepo} --tags ${projectInfo.versionTag}")
            if (tagExists) {
                loggingUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.versionTag} existsin SCM, and CANNOT be reused")
            }
        }
    }
 }
 
 def verifyReleaseCandidateImagesDoNotExistInImageRegistry(def projectInfo) {
    loggingUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN PRE-PROD IMAGE REGISTRY AS ${projectInfo.versionTag}")

    if (projectInfo.versionTag.startsWith(el.cicd.RELEASE_VERSION_PREFIX)) {
        loggingUtils.errorBanner("Release Candidate tags cannot start with '${el.cicd.RELEASE_VERSION_PREFIX}'.",
                    "'${el.cicd.RELEASE_VERSION_PREFIX}' will prefix Release Versions when Release Candidates are promoted.")
    }

    def imageExists = true
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                        usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                        passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
        imageExists = projectInfo.components.find { component ->
            def verifyImageCmd = shCmd.verifyImage(projectInfo.PRE_PROD_ENV,
                                                   'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                                   'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                    component.id,
                                                    projectInfo.versionTag)

            return sh(returnStdout: true, script: verifyImageCmd)
        }
    }

    if (imageExists) {
        def msg = "Version tag exists in pre-prod image registry for ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused"
        loggingUtils.errorBanner("PRODUCTION MANIFEST FOR RELEASE CANDIDATE FAILED for ${projectInfo.versionTag}:", msg)
    }
 }