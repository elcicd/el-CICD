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

        projectInfo.microServicesToRedeploy = projectInfo.microServices.findAll { it.releaseCandidateGitTag }

        if (!projectInfo.microServicesToRedeploy)  {
            loggingUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
    }

    stage('Checkout all release candidate microservice repositories') {
        loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.microServicesToRedeploy.each { microService ->
            microService.srcCommitHash = microService.releaseCandidateGitTag.split('-').last()
            microService.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.preProdEnv}-${microService.srcCommitHash}"
            projectUtils.cloneGitRepo(microService, microService.deploymentBranch)
        }
    }

    stage('Verify release candidate images exist for redeployment') {
        loggingUtils.echoBanner("VERIFY REDEPLOYMENT CAN PROCEED FOR RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}:",
                                 projectInfo.microServicesToRedeploy.collect { it.name }.join(', '))

        def allImagesExist = true
        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                                variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageRepoUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
            def imageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]

            projectInfo.microServicesToRedeploy.each { microService ->
                def imageTag = "${projectInfo.preProdEnv}-${microService.srcCommitHash}"
                def verifyImageCmd =
                    shCmd.verifyImage(projectInfo.PRE_PROD_ENV, 'IMAGE_REPO_ACCESS_TOKEN', microService.id, imageTag)
                def imageFound = sh(returnStdout: true, script: verifyImageCmd).trim()

                def msg = imageFound ? "REDEPLOYMENT CAN PROCEED FOR ${microService.name}" :
                                        "-> ERROR: no image found: ${imageRepo}/${microService.id}:${projectInfo.preProdEnv}-${microService.srcCommitHash}"
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
            ${projectInfo.microServicesToRedeploy.collect { it.name }.join(', ')}
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
                                 "${projectInfo.microServicesToRedeploy.collect { it.name } .join(', ')}")

        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                         variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN')]) {
            projectInfo.microServicesToRedeploy.each { microService ->
                def imageTag = "${projectInfo.preProdEnv}-${microService.srcCommitHash}"
                def msg = "${microService.name}: ${projectInfo.releaseCandidateTag} TAGGED AS ${projectInfo.preProdEnv} and ${imageTag}"

                def tagImageCmd =
                    shCmd.tagImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN', microService.id, projectInfo.releaseCandidateTag, imageTag)

                def tagImageEnvCmd =
                    shCmd.tagImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN', microService.id, projectInfo.releaseCandidateTag, projectInfo.preProdEnv)

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
                        microServices: projectInfo.microServicesToRedeploy,
                        imageTag: projectInfo.preProdEnv,
                        recreateAll: true)
}
