/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    stage("Install/upgrade CICD Jenkins if necessary") {
        loggingUtils.echoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")
        
        onboardingUtils.createNonProdSdlcNamespacesAndPipelines(projectInfo)
    }

    stage('Tear down project SDLC for reinstall requested') {
        loggingUtils.echoBanner("REMOVING STALE NAMESPACES FOR ${projectInfo.id}, IF REQUESTED")

        if (args.reinstallProjectSdlc) {
            sh "helm uninstall ${projectInfo.id} -n ${projectInfo.cicdMasterNamespace}"
        }
    }
    
    stage('Install/upgrade project SDLC resources') {
        loggingUtils.echoBanner("INSTALL/UPGRADE PROJECT ${projectInfo.id} SDLC RESOURCES")
        
        onboardingUtils.createSdlcNamespacesAndPipelines(projectInfo)
    }
    
    jenkinsUtils.configureCicdJenkinsUrls(projectInfo)
    manageDeployKeys([projectInfo: projectInfo, isNonProd: true])

    stage('Push Webhook to GitHub for non-prod Jenkins') {
        loggingUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO EACH GIT REPO")

        projectInfo.components.each { component ->
            githubUtils.pushBuildWebhook(projectInfo, component, 'build-to-dev')
        }

        projectInfo.artifacts.each { artifact ->
            githubUtils.pushBuildWebhook(projectInfo, artifact, 'build-artifact')
        }
    }
}