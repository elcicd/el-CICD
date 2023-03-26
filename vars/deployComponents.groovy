/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def componentsToDeploy = args.componentsToDeploy ?: []
    def componentsToRemove = args.componentsToRemove ?: []

    stage ("Deploy and Remove component(s)") {
        echo "\n--> CLEAN UP ANY PREVIOUSLY FAILED UPGRADES/INSTALLS\n"
        sh """
            COMPONENT_NAMES=\$(helm list --uninstalling --failed  -q  -n ${projectInfo.deployToNamespace})
            if [[ ! -z \${COMPONENT_NAMES} ]]
            then
                for COMPONENT_NAME in \${COMPONENT_NAMES}
                do
                    helm uninstall -n ${projectInfo.deployToNamespace} \${COMPONENT_NAME} --no-hooks
                done
            fi
        """
    
        componentsToDeploy.each { it.flaggedForDeployment = true; it.flaggedForRemoval = false }
        componentsToRemove.each { it.flaggedForDeployment = false; it.flaggedForRemoval = true }
        
        deploymentUtils.runComponentDeploymentStages(projectInfo, componentsToRemove + componentsToDeploy)
        
        deploymentUtils.waitForAllTerminatingPodsToFinish(projectInfo)
    }

    stage('Deployment change summary') {
        def resultsMsgs = ["DEPLOYMENT CHANGE SUMMARY FOR ${projectInfo.deployToNamespace}:", '']
        projectInfo.components.each { component ->
            def deployed = componentsToDeploy?.contains(component)
            def removed = componentsToRemove?.contains(component)
            if (deployed || removed) {
                resultsMsgs += "**********"
                resultsMsgs += ''
                def checkoutBranch = component.deploymentBranch ?: component.scmBranch
                resultsMsgs += deployed ? "${component.name} DEPLOYED FROM GIT:" : "${component.name} REMOVED FROM NAMESPACE"
                if (deployed) {
                    def refs = component.scmBranch.startsWith(component.srcCommitHash) ?
                        "    Git image source ref: ${component.srcCommitHash}" :
                        "    Git image source refs: ${component.scmBranch} / ${component.srcCommitHash}"
                    
                    resultsMsgs += "    Git deployment ref: ${checkoutBranch}"
                    resultsMsgs += "    git checkout ${checkoutBranch}"
                }
                resultsMsgs += ''
            }
        }
        resultsMsgs += "**********"

        loggingUtils.echoBanner(resultsMsgs)
    }
}