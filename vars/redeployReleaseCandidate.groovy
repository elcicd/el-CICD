/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the deploy-to-production pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/deploy-to-production-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.releaseCandidateTag = args.releaseCandidateTag
    projectInfo.deployToEnv = projectInfo.preProdEnv
    projectInfo.deployToNamespace = projectInfo.preProdNamespace

    stage('Gather all git branches, tags, and source commit hashes') {
        loggingUtils.echoBanner("GATHER ALL GIT BRANCHES, TAGS, AND SOURCE COMMIT HASHES")

        deployToProductionUtils.gatherAllVersionGitTagsAndBranches(projectInfo)

        projectInfo.componentsToRedeploy = projectInfo.components.findAll { it.releaseCandidateGitTag }

        if (!projectInfo.componentsToRedeploy)  {
            loggingUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
    }

    stage('Checkout all release candidate component repositories') {
        loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.componentsToRedeploy.each { component ->
            component.srcCommitHash = component.releaseCandidateGitTag.split('-').last()
            component.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.preProdEnv}-${component.srcCommitHash}"
            projectUtils.cloneGitRepo(component, component.deploymentBranch)
        }
    }

    stage('Verify release candidate images exist for redeployment') {
        loggingUtils.echoBanner("VERIFY REDEPLOYMENT CAN PROCEED FOR RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}:",
                                 projectInfo.componentsToRedeploy.collect { it.name }.join(', '))

        def allImagesExist = true
        withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryPullTokenId(projectInfo.preProdEnv),
                                variable: 'IMAGE_REGISTRY_PULL_TOKEN')]) {
            def imageRepoUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REGISTRY_USERNAME_POSTFIX}"]
            def imageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]

            projectInfo.componentsToRedeploy.each { component ->
                def imageTag = "${projectInfo.preProdEnv}-${component.srcCommitHash}"
                def verifyImageCmd =
                    shCmd.verifyImage(projectInfo.PRE_PROD_ENV, 'IMAGE_REGISTRY_PULL_TOKEN', component.id, imageTag)
                def imageFound = sh(returnStdout: true, script: verifyImageCmd).trim()

                def msg = imageFound ? "REDEPLOYMENT CAN PROCEED FOR ${component.name}" :
                                        "-> ERROR: no image found: ${imageRepo}/${component.id}:${projectInfo.preProdEnv}-${component.srcCommitHash}"
                echo msg

                allImagesExist = allImagesExist && imageFound
            }
        }

        if (!allImagesExist) {
            def msg = "BUILD FAILED: Missing image(s) to deploy in ${projectInfo.PRE_PROD_ENV} for release candidate ${projectInfo.releaseCandidateTag}"
            loggingUtils.errorBanner(msg)
        }
    }

    stage('Confirm release candidate deployment') {
        input("""

            ===========================================
            CONFIRM REDEPLOYMENT OF ${projectInfo.releaseCandidateTag} to ${projectInfo.preProdEnv}
            ===========================================

            *******
            -> Microservices included in this release candidate to be deployed:
            ${projectInfo.componentsToRedeploy.collect { it.name }.join(', ')}
            *******

            *******
            -> ${projectInfo.deployToNamespace} will be cleaned of all other project resources before deployment
            *******

            ===========================================
            PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION

            ARE YOU SURE YOU WISH TO PROCEED?
            ===========================================
        """)
    }

    stage('Tag images') {
        loggingUtils.echoBanner("TAG IMAGES TO ${projectInfo.PRE_PROD_ENV}:",
                                 "${projectInfo.componentsToRedeploy.collect { it.name } .join(', ')}")

        withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryPullTokenId(projectInfo.preProdEnv),
                         variable: 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN')]) {
            projectInfo.componentsToRedeploy.each { component ->
                def imageTag = "${projectInfo.preProdEnv}-${component.srcCommitHash}"
                def msg = "${component.name}: ${projectInfo.releaseCandidateTag} TAGGED AS ${projectInfo.preProdEnv} and ${imageTag}"

                def tagImageCmd =
                    shCmd.tagImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.releaseCandidateTag, imageTag)

                def tagImageEnvCmd =
                    shCmd.tagImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.releaseCandidateTag, projectInfo.preProdEnv)

                sh """
                    ${shCmd.echo ''}
                    ${tagImageCmd}

                    ${shCmd.echo ''}
                    ${tagImageEnvCmd}

                    ${shCmd.echo '', '******', msg, '******'}
                """
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        components: projectInfo.componentsToRedeploy,
                        imageTag: projectInfo.preProdEnv,
                        recreateAll: true)
}
