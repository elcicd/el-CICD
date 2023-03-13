/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete all projects and the CICD server for a team.
 */

def call(Map args) {
    def groupId = args.groupId
    def projectNames
    def projectInfos

    def cicdMasterNamespace = "${groupId}-${el.cicd.CICD_MASTER_NAMESPACE_POSTFIX}"
    stage('Gather every project info') {
        loggingUtils.echoBanner("GATHERING ALL PROJECT INFORMATION FOR ${groupId} PROJECTS IN ${cicdMasterNamespace}")
        
        def helmListScript = "helm list --all --short -n ${cicdMasterNamespace} | grep \\\\-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}"
        projectNames = sh(returnStdout: true, script: helmListScript).split('\n')
        
        projectNames = projectNames.collect { it - "-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}" }
        projectInfos = projectNames.collect { projectUtils.gatherProjectInfo(it.trim()) }
    }
    
    stage('Confirm removing cicd server and all project namespaces from cluster') {
        def msg = loggingUtils.echoBanner(
            "${cicdMasterNamespace} will be deleted",
            '',
            '===========================================',
            '',
            'THE FOLLOWING PROJECTS AND THEIR CICD NAMESPACES WILL HAVE THEIR CICD NAMESPACES DELETED:',
            projectNames,
            '',
            'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
            '',
            "Should the CICD Server and all Projects for the Group ${groupId} be removed from the cluster?"
        )

        jenkinsUtils.displayInputWithTimeout(msg)
    }
    
    
    loggingUtils.echoBanner("DELETING DEPLOY KEYS FOR ALL ${groupId} PROJECT MODULES")
    def modules = projectInfos.collectMany { it.modules }
    def buildStages =  concurrentUtils.createParallelStages('Delete SCM deploy keys for all modules of all projects', modules) { module ->
        githubUtils.deleteProjectDeployKeys(module)
    }
    
    parallel(buildStages)

    stage('Tear down all projects and the cicd server') {
        loggingUtils.echoBanner("REMOVING ALL ${groupId} PROJECTS AND THE ${groupId} CICD SERVER FROM THE CLUSTER")
        
        sh """
            helm list --all --short -n ${cicdMasterNamespace} | xargs -L1 helm uninstall --wait -n ${cicdMasterNamespace}
            
            oc delete project ${cicdMasterNamespace}
        """
        
        loggingUtils.echoBanner("ALL PROJECTS FOR THE ${groupId} GROUP AND NAMESPACE ${cicdMasterNamespace} HAVE BEEN REMOVED FROM THE CLUSTER ")
    }
}