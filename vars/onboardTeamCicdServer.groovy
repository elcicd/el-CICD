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
        if (args.rebuildCicdEnvs) {
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
        onboardTeamCicdServerUtils.setupProjectCicdResources(projectInfo)
        
        onboardTeamCicdServerUtils.setupProjectPvResources(projectInfo)

        onboardTeamCicdServerUtils.syncJenkinsPipelines(projectInfo.teamInfo.cicdMasterNamespace)
    }

    projectInfoUtils.setRemoteRepoDeployKeyId(projectInfo)
    
    loggingUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM PROJECT ${projectInfo.id} GIT REPOS")
    def buildStages =  concurrentUtils.createParallelStages('Delete old SCM deploy keys', projectInfo.modules) { module ->
        githubUtils.deleteProjectDeployKeys(module)
    }
    parallel(buildStages)

    loggingUtils.echoBanner("ADDING DEPLOY KEYS FOR PROJECT ${projectInfo.id} GIT REPOS")
    buildStages =  concurrentUtils.createParallelStages('Add SCM deploy keys', projectInfo.modules) { module ->
        dir(module.workDir) {
            githubUtils.addProjectDeployKey(module, "${module.scmDeployKeyJenkinsId}.pub")
        }
    }
    parallel(buildStages)

    stage('Push Webhooks to GitHub') {
        loggingUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO EACH GIT REPO")

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