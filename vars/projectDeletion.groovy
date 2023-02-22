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

        jenkinsUtils.displayInputWithTimeout(msg)
    }

    stage('Delete GitHub deploy keys') {
        loggingUtils.echoBanner("REMOVING ALL DEPLOY KEYS FROM THE SCM FOR PROJECT ${projectInfo.id}")
        
        githubUtils.deleteProjectDeployKeys(projectInfo)
    }

    stage('Remove project from cluster') {
        loggingUtils.echoBanner("REMOVING ALL PROJECT ${projectInfo.id} FROM CLUSTER")
        
        sh """
            helm uninstall ${projectInfo.id}-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX} --wait --no-hooks
        """
        
        onboardingUtils.syncJenkinsPipelines(projectInfo.cicdMasterNamespace)
        
        loggingUtils.echoBanner("${projectInfo.id} REMOVED FROM CLUSTER")
    }
}