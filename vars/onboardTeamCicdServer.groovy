/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    def teamInfo = args.teamInfo
    def projectInfo = args.projectInfo

    stage("Install/upgrade CICD Jenkins") {
        onboardTeamCicdServerUtils.setupTeamCicdServer(teamInfo)
    }

    stage('Uninstall current project CICD resources (optional)') {
        if (args.recreateCicdEnvs) {
            loggingUtils.echoBanner("REBUILDING SLDC ENVIRONMENTS REQUESTED: REMOVING OLD ENVIRONMENTS")

            sh """
                HELM_CHART=\$(helm list -q -n ${projectInfo.teamInfo.cicdMasterNamespace} | grep -E '^${projectInfo.id}\$')
                if [[ "\${HELM_CHART}" ]]
                then
                    helm uninstall --wait ${projectInfo.id} -n ${projectInfo.teamInfo.cicdMasterNamespace}
                else
                    ${shCmd.echo "--> CICD resources not found for project ${projectInfo.id}. Skipping..."}
                fi
            """
        }
        else {
            echo '--> REBUILD CICD ENVIRONMENTS NOT SELECTED; Skipping...'
        }
    }

    stage('Configure project CICD resources') {
        loggingUtils.echoBanner("DEPLOY PIPELINES FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectPipelines(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH PIPELINE CONFIGURATION")
        onboardTeamCicdServerUtils.syncJenkinsPipelines(projectInfo.teamInfo.cicdMasterNamespace)
    }

    stage('Configure project environments') {
        loggingUtils.echoBanner("CREATE CICD ENVIRONMENTS FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectEnvironments(projectInfo)

        loggingUtils.echoBanner("DEPLOY PERSISTENT VOLUMES DEFINITIONS FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.resetProjectPvResources(projectInfo)
        onboardTeamCicdServerUtils.setupProjectPvResources(projectInfo)
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