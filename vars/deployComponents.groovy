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
                resultsMsgs += deployed ? "${component.name} DEPLOYED FROM BRANCH:" : "${component.name} REMOVED"
                if (deployed) {
                    resultsMsgs += "    git checkout ${component.deploymentBranch}"
                }
                resultsMsgs += ''
            }
        }
        resultsMsgs += "**********"

        loggingUtils.echoBanner(resultsMsgs)
    }
}