/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the redeploy component pipeline.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = args.redeployEnv
    projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
    projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]

    stage('Checkout all component repositories') {
        loggingUtils.echoBanner("CLONE ALL MICROSERVICE REPOSITORIES IN PROJECT")

        redeployComponentUtils.checkoutAllRepos(projectInfo)
    }

    stage ('Select components and environment to redeploy to or remove from') {
        loggingUtils.echoBanner("SELECT WHICH COMPONENTS TO REDEPLOY OR REMOVE")
        
        redeployComponentUtils.selectComponentsToRedeploy(projectInfo)
    }

    stage('Verify image(s) exist for environment') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) TO REDEPLOY EXIST IN IMAGE REPOSITORY:",
                                 projectInfo.componentsToRedeploy.collect { "${it.id}:${it.deploymentImageTag}" }.join(', '))

        def allImagesExist = true
        def errorMsgs = ["MISSING IMAGE(s) IN ${projectInfo.deployToNamespace} TO REDEPLOY:"]
        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                          usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                          passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
        {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REGISTRY_USERNAME_POSTFIX}"] + ":\${TO_IMAGE_REGISTRY_PULL_TOKEN}"
            projectInfo.componentsToRedeploy.each { component ->
                def verifyImageCmd =
                    shCmd.verifyImage(projectInfo.ENV_TO,
                                      'TO_IMAGE_REGISTRY_USERNAME',
                                      'TO_IMAGE_REGISTRY_PWD',
                                      component.id,
                                      component.deploymentImageTag)
                                      
                echo verifyImageCmd

                if (!sh(returnStdout: true, script: verifyImageCmd).trim()) {
                    errorMsgs << "    ${component.id}:${projectInfo.deploymentImageTag} NOT FOUND IN ${projectInfo.deployToEnv} (${projectInfo.deployToNamespace})"
                }
            }
        }

        if (errorMsgs.size() > 1) {
            loggingUtils.errorBanner(errorMsgs)
        }
    }

    stage('Checkout all deployment branches') {
        loggingUtils.echoBanner("CHECKOUT ALL DEPLOYMENT BRANCHES")

        projectInfo.componentsToRedeploy.each { component ->
            dir(component.workDir) {
                sh "git checkout ${component.deploymentBranch}"
                component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
            }
        }
    }

    stage('tag images to redeploy for environment') {
        loggingUtils.echoBanner("TAG IMAGES FOR REPLOYMENT IN ENVIRONMENT TO ${projectInfo.deployToEnv}:",
                                 projectInfo.componentsToRedeploy.collect { "${it.id}:${it.deploymentImageTag}" }.join(', '))

        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                          usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                          passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
        {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REGISTRY_USERNAME_POSTFIX}"] + ":\${TO_IMAGE_REGISTRY_PULL_TOKEN}"
            projectInfo.componentsToRedeploy.each { component ->
                def tagImageCmd =
                    shCmd.tagImage(projectInfo.ENV_TO,
                                   'TO_IMAGE_REGISTRY_USERNAME',
                                   'TO_IMAGE_REGISTRY_PWD',
                                   component.id,
                                   component.deploymentImageTag,
                                   projectInfo.deployToEnv)
                sh """
                    ${shCmd.echo '', "--> Tagging image '${component.id}:${component.deploymentImageTag}' as '${component.id}:${projectInfo.deployToEnv}'"}

                    ${tagImageCmd}
                """
            }
        }
    }

    deployComponents(projectInfo: projectInfo,
                     components: projectInfo.componentsToRedeploy,
                     componentsToRemove: projectInfo.components.findAll { it.remove },
                     imageTag: projectInfo.deployToEnv)
}
