/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
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
            
            onboardTeamCicdServerUtils.uninstallSdlcEnvironments(projectInfo)
        }
        else {
            echo '--> RECREATE SDLC ENVIRONMENTS NOT SELECTED: SKIPPING'
        }
    }

    stage('Configure project CICD resources') {
        loggingUtils.echoBanner("DEPLOY PIPELINES FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectPipelines(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION")
        onboardTeamCicdServerUtils.syncJenkinsPipelines(projectInfo.teamInfo.cicdMasterNamespace)
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

    loggingUtils.echoBanner("CONFIGURE GIT DEPLOY KEYS FOR PROJECT ${projectInfo.id}")
    onboardTeamCicdServerUtils.configureScmDeployKeys(projectInfo)

    loggingUtils.echoBanner("PUSH ${projectInfo.id} JENKINS WEBHOOK TO EACH GIT REPO")
    onboardTeamCicdServerUtils.configureScmWebhooks(projectInfo)

    stage('Summary') {
        loggingUtils.echoBanner("Team ${args.teamId} Project ${args.projectId} Onboarding Complete.",
                                '',
                                "CICD Server URL: ${projectInfo.jenkinsHostUrl}")
    }
}