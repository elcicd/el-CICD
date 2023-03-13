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
        
        de helmListScript = "helm list --all --short -n ${cicdMasterNamespace} | grep \\-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}"
        def projectNames = sh(returnStdout: true, script: helmListScript).split('n')
        
        projectNames = projectNames.collect { it - "-${el.cicd.HELM_RELEASE_PROJECT_SUFFIX}$" }
        def projectInfos = projectNames.collect { projectUtils.gatherProjectInfo(it.trim()) }
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

    stage('Delete SCM deploy keys for all projects') {
        loggingUtils.echoBanner("DELETING DEPLOY KEYS FOR ALL ${groupId} PROJECTS")
        
        projectInfos.each {
            githubUtils.deleteProjectDeployKeys(projectInfo)
        }
    }

    stage('Tear down all projects and the cicd server') {
        loggingUtils.echoBanner("REMOVING ALL ${groupId} PROJECTS AND THE ${groupId} CICD SERVER FROM THE CLUSTER")
        
        sh """
            helm list --all --short -n ${cicdMasterNamespace} | xargs -L1 helm uninstall --wait -n ${cicdMasterNamespace}
            oc wait --for=delete pods -l 'component in (${componentNames})' -n ${projectInfo.deployToNamespace} --timeout=600s
            
            oc delete project ${cicdMasterNamespace}
        """
        
        loggingUtils.echoBanner("ALL PROJECTS FOR GROUP ${groupId} HAVE BEEN REMOVED FROM THE CLUSTER AND ${cicdMasterNamespace} HAS BEEN DELETED")
    }
}