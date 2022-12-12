/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    stage('Uninstall project SDLC for baseline reinstall, if requested') {
        if (args.reinstallProjectSdlc) {
            loggingUtils.echoBanner("REMOVING STALE NAMESPACES FOR ${projectInfo.id}, IF REQUESTED")

            sh "helm uninstall ${projectInfo.id} -n ${projectInfo.cicdMasterNamespace}"
        }
    }

    stage("Install/upgrade CICD Jenkins if necessary") {
        onboardingUtils.setupClusterWithProjecCicdServer(projectInfo)
    }
    
    stage('Install/upgrade project SDLC resources') {        
        onboardingUtils.setupClusterWithProjectCicdResources(projectInfo)
    }
    
    manageCicdCredentials([projectInfo: projectInfo, isNonProd: true])

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