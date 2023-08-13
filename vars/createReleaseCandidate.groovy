/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-release-candidate-template
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.versionTag = args.versionTag

    stage('Verify version tag does not exist in SCM') {
        loggingUtils.echoBanner("VERIFY THE TAG ${projectInfo.versionTag} DOES NOT EXIST IN ANY COMPONENT\'S SCM REPOSITORY")

        createReleaseCandidateUtils.verifyVersionTagDoesNotExistInScm(projectInfo)
    }

    stage('Verify version tag does NOT exist in pre-prod image registry') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN PRE-PROD IMAGE REGISTRY AS ${projectInfo.versionTag}")

        createReleaseCandidateUtils.verifyReleaseCandidateImagesDoNotExistInImageRegistry(projectInfo)
    }

    stage("Checkout all components deployed in ${projectInfo.PRE_PROD_ENV}") {
        loggingUtils.echoBanner("CHECK OUT ALL COMPONENTS DEPLOYED IN ${projectInfo.PRE_PROD_ENV}")
        
        createReleaseCandidateUtils.checkoutAllAvailableComponents(projectInfo)
    }

    stage ('Select components to tag as Release Candidate') {
        loggingUtils.echoBanner("SELECT COMPONENTS TO TAG AS RELEASE CANDIDATE ${projectInfo.versionTag}",
                                '',
                                "NOTE: Only components currently deployed in ${projectInfo.preProdNamespace} will be considered")

        createReleaseCandidateUtils.selectReleaseCandidateComponents(projectInfo)
    }

    stage('Confirm production manifest for release version') {
        createReleaseCandidateUtils.confirmReleaseCandidateManifest(projectInfo, args)
    }

    stage('Tag all images') {
        loggingUtils.echoBanner("TAG ALL RELEASE CANDIDATE IMAGES IN ${projectInfo.preProdEnv} AS ${projectInfo.versionTag}")

        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                         passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
            projectInfo.componentsToTag.each { component ->
                def tagImageCmd = shCmd.tagImage(projectInfo.PRE_PROD_ENV,
                                                 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                                 'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                 component.id,
                                                 projectInfo.preProdEnv,
                                                 projectInfo.versionTag)

                def msg = "Image ${component.id}:${projectInfo.preProdEnv} tagged as ${component.id}:${projectInfo.versionTag}"
                sh """
                    ${shCmd.echo ''}
                    ${tagImageCmd}
                    ${shCmd.echo(msg)}
                """
            }
        }
    }

    stage('Tag all images and configuration commits') {
        loggingUtils.echoBanner("TAG GIT DEPLOYMENT COMMIT HASHES ON EACH MICROSERVICE DEPLOYMENT BRANCH FOR RELEASE CANDIDATE ${projectInfo.versionTag}")

        projectInfo.components.each { component ->
            if (component.promote) {
                dir(component.workDir) {
                    withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                        def gitReleaseCandidateTag = "${projectInfo.versionTag}-${component.srcCommitHash}"
                        sh """
                            CUR_BRANCH=`git rev-parse --abbrev-ref HEAD`
                            ${shCmd.echo "--> Tagging Release Candidate in '${component.scmRepoName}' in branch '\${CUR_BRANCH}' as '${gitReleaseCandidateTag}'"}
                            ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git tag ${gitReleaseCandidateTag}", "git push --tags")}
                        """
                    }
                }
            }
        }
    }
}