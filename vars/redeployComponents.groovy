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

    loggingUtils.echoBanner('CLONE ALL MICROSERVICE REPOSITORIES IN PROJECT')

    stage('Clone all component repos') {
        redeployComponentUtils.checkoutAllRepos(projectInfo)
    }

    stage ('Select components and environment to redeploy to or remove from') {
        loggingUtils.echoBanner("SELECT WHICH COMPONENTS TO REDEPLOY OR REMOVE")

        redeployComponentUtils.selectComponentsToRedeploy(projectInfo)
    }

    stage('Verify selected component image(s) exists') {
        redeployComponentsUtils.runVerifyImagesExistStages(projectInfo, projectInfo.componentsToRedeploys)
    }

    stage('Checkout all deployment branches') {
        loggingUtils.echoBanner('CHECKOUT ALL DEPLOYMENT BRANCHES')

        projectInfo.componentsToRedeploy.each { component ->
            dir(component.workDir) {
                sh "git checkout ${component.deploymentBranch}"
                component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
            }
        }
    }

    stage('Tag image(s) for deployment') {
        redeployComponentUtils.runTagImagesStages(projectInfo)
    }

    deployComponents(projectInfo: projectInfo,
                     components: projectInfo.componentsToRedeploy,
                     componentsToRemove: projectInfo.components.findAll { it.remove },
                     imageTag: projectInfo.deployToEnv)
}
