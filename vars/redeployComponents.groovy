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

    loggingUtils.echoBanner("CLONE ALL MICROSERVICE REPOSITORIES IN PROJECT")

    redeployComponentUtils.checkoutAllRepos(projectInfo)

    stage ('Select components and environment to redeploy to or remove from') {
        loggingUtils.echoBanner("SELECT WHICH COMPONENTS TO REDEPLOY OR REMOVE")

        redeployComponentUtils.selectComponentsToRedeploy(projectInfo)
    }

    def verifedMsgs = ["IMAGE(s) VERIFED TO EXIST IN THE ${projectInfo.ENV_TO} IMAGE REPOSITORY:"]
    def errorMsgs = ["MISSING IMAGE(s) IN THE ${projectInfo.ENV_TO} IMAGE REPOSITORY:"]

    concurrentUtils.runVerifyImagesInRegistryStages(projectInfo,
                                                    projectInfo.componentsToRedeploy,
                                                    projectInfo.deployToEnv,
                                                    verifedMsgs,
                                                    errorMsgs)

    if (verifedMsgs.size() > 1) {
        loggingUtils.echoBanner(verifedMsgs)
    }
}
