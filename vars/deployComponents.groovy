/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def componentsToDeploy = args.componentsToDeploy
    def componentsToRemove = args.componentsToRemove

    def componentNames = componentsToDeploy ? componentsToDeploy.collect { it.name } .join(' ') : ''
    componentNames += componentsToRemove ? componentsToRemove.collect { it.name } .join(' ') : ''
    sh """
        if [[ ! -z \$(helm list --uninstalling --failed  -q  -n ${projectInfo.deployToNamespace}) ]]
        then
            for COMPONENT_NAME in ${componentNames}
            do
                helm uninstall -n ${projectInfo.deployToNamespace} \${COMPONENT_NAME} --no-hooks
            done
        fi
    """

    stage('Uninstall component(s)') {
        def componentsToUninstall = args.recreateAll ? projectInfo.components : (componentsToRemove ? componentsToRemove.collect() : [])
        if (args.recreate && componentsToDeploy) {
            componentsToUninstall += componentsToDeploy
        }
        
        if (args.recreate && componentsToDeploy) {
            loggingUtils.echoBanner("RECREATE SELECTED, REMOVING INSTALLED COMPONENTS:", componentsToDeploy.collect { it.name }.join(', '))
        }
        else if (args.recreateAll) {
            loggingUtils.echoBanner("RECREATE ALL SELECTED.  ALL DEPLOYED COMPONENTS TO BE REMOVED.")
        }

        deploymentUtils.runComponentRemovalStages(projectInfo, componentsToUninstall)

        if (componentsToUninstall) {
            deploymentUtils.waitForAllTerminatingPodsToFinish(projectInfo)
        }
    }

    stage ("Install/Upgrade component(s)") {
        deploymentUtils.runComponentDeploymentStages(projectInfo, componentsToDeploy)
        
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