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

    stage('Configure project CICD pipelines') {
        loggingUtils.echoBanner("DEPLOY PIPELINES FOR PROJECT ${projectInfo.id}")
        onboardProjectUtils.setupProjectPipelines(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION")
        projectUtils.syncJenkinsPipelines(projectInfo.teamInfo)
    }

    stage('Configure project SDLC environments') {
        loggingUtils.echoBanner("CREATE SDLC ENVIRONMENTS FOR PROJECT ${projectInfo.id}")
        onboardProjectUtils.setupProjectEnvironments(projectInfo)

        onboardProjectUtils.resetProjectPvResources(projectInfo)
        if (projectInfo.staticPvs) {
            loggingUtils.echoBanner("DEPLOY PERSISTENT VOLUMES DEFINITIONS FOR PROJECT ${projectInfo.id}")
            onboardProjectUtils.setupProjectPvResources(projectInfo)
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