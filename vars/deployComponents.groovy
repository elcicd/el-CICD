/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def componentsToDeploy = args.componentsToDeploy ?: []
    def componentsToRemove = args.componentsToRemove ?: []

    if (!componentsToDeploy && !componentsToRemove) {
        loggingUtils.errorBanner('NO COMPONENTS TO DEPLOY OR REMOVE')
    }


    stage ('Clean up failed upgrades/installs') {
        loggingUtils.echoBanner('CLEAN UP ANY PREVIOUSLY FAILED UPGRADES/INSTALLS')
        deployComponentsUtils.cleanupFailedInstalls(projectInfo)
    }

    deployComponentsUtils.removeComponents(projectInfo, componentsToRemove)
            
    if (!componentsToRemove) {     
        echo '--> NO COMPONENTS TO REMOVE: SKIPPING'
    }

    loggingUtils.echoBanner('SETUP COMPONENT(S) DEPLOYMENT DIRECTORY:', componentsToDeploy.collect { it.name }.join(', '))

    deployComponentsUtils.setupDeploymentDirs(projectInfo, componentsToDeploy)
            
    if (!componentsToDeploy) {
        echo '--> NO COMPONENTS TO DEPLOY: SKIPPING'
    }

    loggingUtils.echoBanner('DEPLOY COMPONENT(S):', componentsToDeploy.collect { it.name }.join(', '))

    // deployComponentsUtils.runComponentDeploymentStages(projectInfo, componentsToDeploy)
    
    if (!componentsToDeploy) {
        echo '--> NO COMPONENTS TO DEPLOY: SKIPPING'
    }
    
    echo 'whatever'
    sleep 5
    def componentsToTest = deployComponentsUtils.getTestComponents(projectInfo, componentsToDeploy)
    echo 'back'
    sleep 5
    
    loggingUtils.echoBanner('RUNNING TEST COMPONENT(S):', componentsToTest.collect { it.name }.join(', '))
    
    deployComponentsUtils.runTestComponents(projectInfo, componentsToTest)
    
    stage('Summary') {
        componentsToRemove.each { it.flaggedForRemoval = true }
        componentsToDeploy.each { it.flaggedForDeployment = true }
        componentsToTest.each { it.flaggedForTest }
        
        
        deployComponentsUtils.outputDeploymentSummary(projectInfo)
    }
}