/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def call(Map args) {
    def teamInfo = args.teamInfo
    def projectInfo = args.projectInfo
    def recreateSdlcEnvs = args.recreateSdlcEnvs

    stage("Install/upgrade CICD Jenkins") {
        onboardTeamCicdServerUtils.setupTeamCicdServer(teamInfo)
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

    stage('Configure project CICD resources') {
        loggingUtils.echoBanner("DEPLOY PIPELINES FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectPipelines(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION")
        projectUtils.syncJenkinsPipelines(projectInfo)
    }

    stage('Configure project SDLC environments') {
        loggingUtils.echoBanner("CREATE SDLC ENVIRONMENTS FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectEnvironments(projectInfo)

        onboardTeamCicdServerUtils.resetProjectPvResources(projectInfo)
        if (projectInfo.staticPvs) {
            loggingUtils.echoBanner("DEPLOY PERSISTENT VOLUMES DEFINITIONS FOR PROJECT ${projectInfo.id}")
            onboardTeamCicdServerUtils.setupProjectPvResources(projectInfo)
        }
        else {
            echo("--> NO PERSISTENT VOLUME DEFINITIONS DEFINED FOR PROJECT ${projectInfo.id}: SKIPPING")
        }
    }

    loggingUtils.echoBanner("ADD DEPLOY KEYS TO EACH GIT REPO FOR PROJECT ${projectInfo.id}")
    projectUtils.createNewGitDeployKeysForProject(projectInfo)

    loggingUtils.echoBanner("ADD WEBHOOKS TO EACH GIT REPO FOR PROJECT ${projectInfo.id}")
    projectUtils.createNewGitWebhooksForProject(projectInfo)

    stage('Summary') {
        loggingUtils.echoBanner("Team ${args.teamId} Project ${args.projectId} Onboarding Complete.",
                                '',
                                "CICD Server URL: ${projectInfo.jenkinsHostUrl}")
    }
}