/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete a project from OKD; i.e. the opposite of onboarding a project.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    
    stage('Confirm removing project and namespaces from cluster') {
        def msg = loggingUtils.echoBanner(
            "ALL CICD NAMESPACES FOR ${projectInfo.id} WILL BE REMOVED FROM THE CLUSTER",
            '',
            "ALL DEPLOY KEYS FOR FOR ${projectInfo.id} WILL BE REMOVED FROM THE SCM",
            ''
            "Should project ${projectInfo.id} be deleted?"
        )

        jenkinsUtils.displayInputWithTimeout(msg, args)
    }

    loggingUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM PROJECT ${projectInfo.id} GIT REPOS")
    def buildStages =  concurrentUtils.createParallelStages('Delete SCM deploy keys', projectInfo.modules) { module ->
        githubUtils.deleteProjectDeployKeys(module)
    }    
    parallel(buildStages)

    stage('Remove project from cluster') {
        loggingUtils.echoBanner("REMOVING ALL PROJECT ${projectInfo.id} FROM CLUSTER")
        
        sh """
            helm uninstall ${projectInfo.id}-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX} --wait --no-hooks
        """
        
        nonProdOnboardingUtils.syncJenkinsPipelines(projectInfo.cicdMasterNamespace)
        
        loggingUtils.echoBanner("${projectInfo.id} REMOVED FROM CLUSTER")
    }
}