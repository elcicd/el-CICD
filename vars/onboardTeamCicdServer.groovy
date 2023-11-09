/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardTeamCicdServerUtils.init()

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
                if [[ ! -z \${HELM_CHART} ]]
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

    stage('Install/upgrade project CICD resources') {
        loggingUtils.echoBanner("DEPLOY RESOURCES ${projectInfo.teamInfo.cicdMasterNamespace} TO SUPPORT PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectCicdResources(projectInfo)

        loggingUtils.echoBanner("CONFIGURE CLUSTER TO SUPPORT NON-PROD STATIC PERSISTENT VOLUMES FOR ${projectInfo.id}")
        onboardTeamCicdServerUtils.resetProjectPvResources(projectInfo)
        onboardTeamCicdServerUtils.setupProjectPvResources(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH CONFIGURATION")
        onboardTeamCicdServerUtils.syncJenkinsPipelines(projectInfo.teamInfo.cicdMasterNamespace)
    }

    stage('Configure SCM deploy keys') {
        loggingUtils.echoBanner("CONFIGURE SCM DEPLOY KEYS FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.configureDeployKeys(projectInfo)
    }

    stage('Push Webhooks to SCM') {
        loggingUtils.echoBanner("PUSH ${projectInfo.id} JENKINS WEBHOOK TO EACH SCM REPO")

        jenkinsUtils.configureCicdJenkinsUrls(projectInfo)
        projectInfo.buildModules.each { module ->
            if (!module.disableWebhook) {
                githubUtils.pushBuildWebhook(module, module.isComponent ? 'build-to-dev' : 'build-artifact')
            }
            else {
                echo "-->  WARNING: WEBHOOK FOR ${module.name} MARKED AS DISABLED.  SKIPPING..."
            }
        }
    }

    loggingUtils.echoBanner("Team ${args.teamId} Project ${args.projectId} Onboarding Complete.", "CICD Server URL: ${projectInfo.jenkinsUrls.HOST}")
}