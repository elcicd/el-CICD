/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    stage("Install/upgrade CICD Jenkins") {
        onboardingUtils.setupProjectCicdServer(projectInfo)
    }

    stage('Uninstall current project CICD resources (optional)') {
        if (args.rebuildCicdEnvs) {
            loggingUtils.echoBanner("REBUILDING SLDC ENVIRONMENTS REQUESTED: REMOVING OLD ENVIRONMENTS")

            sh """
                if [[ ! -z \$(helm list -q | grep -E '^${projectInfo.id}\$) ]]
                then
                    helm uninstall ${projectInfo.id} -n ${projectInfo.cicdMasterNamespace}"
                
                    oc wait --for=delete namespace ${projectInfo.nonProdNamespaces.values().join(' ')} --timeout=600s
                else
                    ${shCmd.echo "--> CICD resources not found for project ${projectInfo.id}. Skipping..."}
                fi
            """
        }
    }

    stage('Install/upgrade project CICD resources') {
        onboardingUtils.setupProjectNfsPvResources(projectInfo)
        
        onboardingUtils.setupProjectCicdResources(projectInfo)

        onboardingUtils.syncJenkinsPipelines(projectInfo.cicdMasterNamespace)
    }

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