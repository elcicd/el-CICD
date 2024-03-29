/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def call(Map args) {
    def teamInfo = args.teamInfo
    def projectInfo = args.projectInfo
    def recreateSdlcEnvs = args.recreateSdlcEnvs

    stage("Install/upgrade CICD Jenkins") {
        onboardProjectUtils.setupTeamCicdServer(teamInfo)
    }

    stage('Recreate SDLC environments [optional]') {
        if (recreateSdlcEnvs) {
            loggingUtils.echoBanner("RECREATE SLDC ENVIRONMENTS REQUESTED: UNINSTALLING")
            
            projectUtils.uninstallSdlcEnvironments(projectInfo)
        }
        else {
            echo '--> RECREATE SDLC ENVIRONMENTS NOT SELECTED: SKIPPING'
        }
    }

    stage('Configure project SDLC environments') {
        loggingUtils.echoBanner("SETUP SDLC ENVIRONMENTS FOR PROJECT ${projectInfo.id}")
        onboardProjectUtils.setProjectSdlc(projectInfo)
    }

    stage('Configure project CICD pipelines') {
        loggingUtils.echoBanner("DEPLOY PIPELINES FOR PROJECT ${projectInfo.id}")
        onboardProjectUtils.setupProjectPipelines(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION")
        projectUtils.syncJenkinsPipelines(projectInfo.teamInfo)
    }
    
    stage('Configure project credentials') {
        loggingUtils.echoBanner("CONFIGURE PROJECT CREDENTIALS")
        onboardProjectUtils.setupProjectCredentials(projectInfo)

        loggingUtils.echoBanner("ADD DEPLOY KEYS TO EACH GIT REPO FOR PROJECT ${projectInfo.id}")
        projectUtils.createNewGitDeployKeysForProject(projectInfo.modules)

        loggingUtils.echoBanner("ADD WEBHOOKS TO EACH GIT REPO FOR PROJECT ${projectInfo.id}")
        projectUtils.createNewGitWebhooksForProject(projectInfo.components + projectInfo.artifacts)
    }

    stage('Summary') {
        loggingUtils.echoBanner("Team ${args.teamId} Project ${args.projectId} Onboarding Complete.",
                                '',
                                "CICD Server URL: ${projectInfo.jenkinsHostUrl}")
    }
}