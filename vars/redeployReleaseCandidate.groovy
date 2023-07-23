/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the deploy-to-production pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/deploy-to-production-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfoUtils.versionTag = args.versionTag

    projectInfo.deployToEnv = projectInfo.preProdEnv
    projectInfo.deployToNamespace = projectInfo.preProdNamespace

    stage('Gather all git branches, tags, and source commit hashes') {
        loggingUtils.echoBanner("GATHER ALL GIT BRANCHES, TAGS, AND SOURCE COMMIT HASHES")

        deployToProductionUtils.gatherAllVersionGitTagsAndBranches(projectInfo)

        projectInfo.componentsToRedeploy = projectInfo.components.findAll { it.releaseCandidateGitTag }
        projectInfo.componentsToRemove = projectInfo.components.findAll { !it.releaseCandidateGitTag }

        if (!projectInfo.componentsToRedeploy)  {
            loggingUtils.errorBanner("UNABLE TO FIND ANY COMPONENTS TAGGED IN THE SCM FOR RELEASE AS ${projectInfo.versionTag}")
        }
    }

    stage('Checkout all release candidate component repositories') {
        loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.componentsToRedeploy.each { component ->
            component.srcCommitHash = component.releaseCandidateGitTag.split('-').last()
            component.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.preProdEnv}-${component.srcCommitHash}"
            projectInfoUtils.cloneGitRepo(component, component.deploymentBranch)
        }
    }

    stage('Verify release candidate images exist for redeployment') {
        loggingUtils.echoBanner("VERIFY REDEPLOYMENT CAN PROCEED FOR RELEASE CANDIDATE ${projectInfo.versionTag}:",
                                projectInfo.componentsToRedeploy.collect { it.name }.join(', '))

        def allImagesExist = true
        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                         passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
            def imageRepoUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REGISTRY_USERNAME_POSTFIX}"]
            def imageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]

            projectInfo.componentsToRedeploy.each { component ->
                def imageTag = "${projectInfo.preProdEnv}-${component.srcCommitHash}"
                
                def verifyImageCmd = shCmd.verifyImage(projectInfo.PRE_PROD_ENV,
                                                       'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                                       'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                       component.id,
                                                       imageTag)
                def imageFound = sh(returnStdout: true, script: verifyImageCmd).trim()

                def msg = imageFound ? "REDEPLOYMENT CAN PROCEED FOR ${component.name}" :
                                        "--> ERROR: no image found: ${imageRepo}/${component.id}:${projectInfo.preProdEnv}-${component.srcCommitHash}"
                echo msg

                allImagesExist = allImagesExist && imageFound
            }
        }

        if (!allImagesExist) {
            def msg = "BUILD FAILED: Missing image(s) to deploy in ${projectInfo.PRE_PROD_ENV} for release candidate ${projectInfo.versionTag}"
            loggingUtils.errorBanner(msg)
        }
    }

    stage('Confirm release candidate deployment') {
        def msg = loggingUtils.echoBanner(
            "CONFIRM REDEPLOYMENT OF ${projectInfo.versionTag} to ${projectInfo.deployToNamespace}",
            '',
            '===========================================',
            '',
            "--> Components in verion ${projectInfo.versionTag} to be deployed:",
            projectInfo.componentsToRedeploy.collect { it.name }.join(', '),
            '',
            '---',
            '',
            "--> Components to be removed from ${projectInfo.deployToNamespace} if present:",
            projectInfo.componentsToRemove.collect { it.name }.join(', '),
            '',
            '===========================================',
            '',
            'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
            '',
            "Should Release Candidate ${projectInfo.versionTag} be redeployed in ${projectInfo.deployToNamespace}?"
        )

        jenkinsUtils.displayInputWithTimeout(msg, args)
    }

    stage('Tag images') {
        loggingUtils.echoBanner("TAG IMAGES TO ${projectInfo.PRE_PROD_ENV}:",
                                 "${projectInfo.componentsToRedeploy.collect { it.name } .join(', ')}")

        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                         passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
            projectInfo.componentsToRedeploy.each { component ->
                def imageTag = "${projectInfo.preProdEnv}-${component.srcCommitHash}"
                def msg = "${component.name}: ${imageTag} TAGGED AS ${projectInfo.preProdEnv}"

                def tagImageEnvCmd =
                    shCmd.tagImage(projectInfo.PRE_PROD_ENV,
                                   'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                   'PRE_PROD_IMAGE_REGISTRY_PWD',
                                   component.id,
                                   imageTag,
                                   projectInfo.preProdEnv)

                sh """
                    ${shCmd.echo ''}
                    ${tagImageEnvCmd}

                    ${shCmd.echo '', '******', msg, '******'}
                """
            }
        }
    }

    deployComponents(projectInfo: projectInfo,
                     componentsToDeploy: projectInfo.componentsToRedeploy,
                     imageTag: projectInfo.preProdEnv,
                     recreateAll: true)
}
