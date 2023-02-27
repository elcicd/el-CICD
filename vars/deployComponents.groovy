/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def components = args.components
    def componentsToRemove = args.componentsToRemove

    def envCaps = (projectInfo.deployToNamespace - projectInfo.id).toUpperCase()

    stage("Purge all failed helm chart installs") {
        def componentNames = components ? components.collect { it.name } .join(' ') : ''
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
    }

    stage('Remove one or more selected components prior to rebuild, if selected') {
        def removalStages
        def recreateComponents = args.recreate ? components : (args.recreateAll ? projectInfo.components : null)

        if (recreateComponents) {
            loggingUtils.echoBanner("REMOVING THE FOLLOWING COMPONENTS BEFORE BUILDING:", componentsToRemove.collect { it.name }.join(', '))

            removalStages = deploymentUtils.createComponentRemovalStages(projectInfo, recreateComponents)
            parallel(removalStages)
            
            deploymentUtils.waitForAllTerminatingPodsToFinish(projectInfo)
        }
        else {
            echo "REINSTALL NOT SELECTED: COMPONENTS ALREADY DEPLOYED WILL BE UPGRADED"
        }
    }

    stage ("Adding and/or removing components") {
        def deployAndRemoveStages = [:]
        def echoBanner = []

        if (components) {
            echoBanner += "DEPLOYING THE FOLLOWING COMPONENTS:"
            echoBanner += components.collect { it.name }.join(', ')
            deployAndRemoveStages.putAll(deploymentUtils.createComponentDeployStages(projectInfo, components))
        }

        if (componentsToRemove) {
            if (echoBanner) {
                echoBanner += ''
            }
            echoBanner += "REMOVING THE FOLLOWING COMPONENTS:"
            echoBanner += componentsToRemove.collect { it.name }.join(', ')
            deployAndRemoveStages.putAll(deploymentUtils.createComponentRemovalStages(projectInfo, componentsToRemove))
        }

        if (deployAndRemoveStages) {
                loggingUtils.echoBanner(echoBanner)
                parallel(deployAndRemoveStages)
        }
        else {
            loggingUtils.echoBanner("NO COMPONENTS TO REMOVE OR DEPLOY: SKIPPING")
        }
    }

    stage ("Wait for all pods to terminate") {
        deploymentUtils.waitForAllTerminatingPodsToFinish(projectInfo)
    }

    stage('Inform users of success') {
        def resultsMsgs = ["DEPLOYMENT CHANGE SUMMARY FOR ${projectInfo.deployToNamespace}:"]
        projectInfo.components.each { component ->
            resultsMsgs += "**********"
            resultsMsgs += ''
            if (components?.contains(component)) {
                resultsMsgs += "${component.name} DEPLOYED FROM BRANCH:"
                resultsMsgs += "    git checkout ${component.deploymentBranch}"
            }
            else if (componentsToRemove?.contains(component)) {
                resultsMsgs += "${component.name} REMOVED"
            }
            resultsMsgs += ''
        }
        resultsMsgs += "**********"

        loggingUtils.echoBanner(resultsMsgs)
    }
}