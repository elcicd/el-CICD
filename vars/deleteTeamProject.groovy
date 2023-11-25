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


    loggingUtils.echoBanner("REMOVE DEPLOY KEYS FROM EACH GIT REPO FOR PROJECT ${projectInfo.id}")
    projectUtils.removeGitDeployKeysFromProject(projectInfo)

    loggingUtils.echoBanner("REMOVE WEBHOOKS FROM EACH GIT REPO FOR PROJECT ${projectInfo.id}")
    projectUtils.removeGitWebhooksFromProject(projectInfo)

    stage('Remove project from cluster') {
        loggingUtils.echoBanner("REMOVING PROJECT ${projectInfo.id} FROM CLUSTER")
        
        sh """
            if [[ -n "\$(helm list -q -n ${projectInfo.teamInfo.cicdMasterNamespace} --filter ${projectInfo.id}-${el.cicd.PIPELINES_POSTFIX})" ]]
            then
                helm uninstall --wait ${projectInfo.id}-${el.cicd.PIPELINES_POSTFIX} -n ${projectInfo.teamInfo.cicdMasterNamespace}
            else
                ${shCmd.echo "--> PIPELINES FOR PROJECT ${projectInfo.id} NOT FOUND; SKIPPING"}
            fi
            
            if [[ "${tearDownSdlcEnvironments ? 'true' : ''}" ]]
            then
                if [[ -n "\$(helm list -q ${projectInfo.id}-${el.cicd.ENVIRONMENTS_POSTFIX} -n ${projectInfo.teamInfo.cicdMasterNamespace})" ]]
                then
                    helm uninstall --wait ${projectInfo.id}-${el.cicd.ENVIRONMENTS_POSTFIX} -n ${projectInfo.teamInfo.cicdMasterNamespace}
                else
                    ${shCmd.echo "--> SDLC ENVIRONMENTS FOR PROJECT ${projectInfo.id} NOT FOUND; SKIPPING"}
                fi
            fi
        """
        
        projectUtils.syncJenkinsPipelines(projectInfo)
        
        loggingUtils.echoBanner("PROJECT ${projectInfo.id} REMOVED FROM CLUSTER")
    }
}