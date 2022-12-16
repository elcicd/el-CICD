/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def components = args.components
    def componentsToRemove = args.componentsToRemove

    def envCaps = (projectInfo.deployToNamespace - projectInfo.id).toUpperCase()

    stage('Remove selected components to be redeployed from scratch, if selected') {
        def removalStages
        def recreateComponents = args.recreate ? components : (args.recreateAll ? projectInfo.components : null)
        
        if (recreateComponents) {
            removalStages = deploymentUtils.createComponentRemovalStages(projectInfo, recreateComponents)
            parallel(removalStages)
        }
        else {
            echo "RECREATE NOT SELECTED: SKIPPING REMOVE ALL MICROSERVICES TO BE DEPLOYED"
        }
    }

    def deployAndRemoveStages = [:]
    def echoBanner = []
    
    if (components) {
        echoBanner += "DEPLOYING THE FOLLOWING COMPONENTS:"
        echoBanner += component.collect { it.name }.join(', ')
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
        loggingUtils.echoBanner("NO MICROSERVICES TO REMOVE OR DEPLOY: SKIPPING")
    }
    
    if (components.find { it.deploymentBranch}) {
        stage('Inform users of success') {
            def checkoutMsgs = []
            components.each { component ->
                checkoutMsgs += ''
                checkoutMsgs += "**********"
                checkoutMsgs += "DEPLOYMENT BRANCH FOR ${component.name}: ${component.deploymentBranch}"
                checkoutMsgs += "git checkout ${component.deploymentBranch}"
                checkoutMsgs += "**********"
            }

            loggingUtils.echoBanner("DEPLOYMENT COMPLETE.  CURRENT DEPLOYMENT BRANCHES FOR PATCHING IN ${projectInfo.deployToNamespace}:", checkoutMsgs)
        }
    }
}