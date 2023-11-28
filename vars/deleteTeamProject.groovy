/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def tearDownSdlcEnvironments = args.tearDownSdlcEnvironments
    
    stage('Confirm project removal') {
        def forProject = "for project ${projectInfo.id}"
        def willBeRemoved = "WILL BE REMOVED FROM"
        def envsMsg = "All SDLC NAMESPACES ${forProject} "
        envsMsg += tearDownSdlcEnvironments ?
            "${willBeRemoved} from the cluster" : "WILL BE RETAINED in the cluster"
        def msg = loggingUtils.echoBanner(
            envsMsg,
            '',
            "All PIPELINES ${forProject} ${willBeRemoved} THE JENKINS SERVER",
            '',
            "All DEPLOY KEYS and WEBHOOKS ${forProject} ${willBeRemoved} THE GIT SERVER",
            '',
            "Should project ${projectInfo.id} be deleted?"
        )

        jenkinsUtils.displayInputWithTimeout(msg, args)
    }

    stage('Remove project from cluster') {
        loggingUtils.echoBanner("REMOVING PROJECT ${projectInfo.id} FROM CLUSTER")
        
        sh """
            CHARTS_TO_REMOVE=\$(helm list -q -n ${projectInfo.teamInfo.cicdMasterNamespace} --filter '${projectInfo.id}-*' | tr '\n' ' ')
            if [[ -z "${tearDownSdlcEnvironments ? 'true' : ''}" ]]
            then
                CHARTS_TO_REMOVE=\${CHARTS_TO_REMOVE/${projectInfo.id}-${el.cicd.ENVIRONMENTS_POSTFIX}/}
            fi
        
            for CHART_TO_REMOVE in \${CHARTS_TO_REMOVE}
            do
                helm uninstall --wait \${CHART_TO_REMOVE} -n ${projectInfo.teamInfo.cicdMasterNamespace}
            done
        """
        
        projectUtils.syncJenkinsPipelines(projectInfo.teamInfo)

        loggingUtils.echoBanner("REMOVE DEPLOY KEYS FROM EACH GIT REPO FOR PROJECT ${projectInfo.id}")
        projectUtils.removeGitDeployKeysFromProject(projectInfo.modules)

        loggingUtils.echoBanner("REMOVE WEBHOOKS FROM EACH GIT REPO FOR PROJECT ${projectInfo.id}")
        projectUtils.removeGitWebhooksFromProject(projectInfo.components + projectInfo.artifacts)
        
        loggingUtils.echoBanner("PROJECT ${projectInfo.id} REMOVED FROM CLUSTER")
    }
}