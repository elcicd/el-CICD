/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select environment, module(s), and branch") {
        getSelectedModules(projectInfo)
    }

    stage("Clean ${projectInfo.deployToNamespace}") {
        if (projectInfo.cleanNamespace) {
            def msg = "CLEANING NAMESPACE ${projectInfo.deployToNamespace}: ALL DEPLOYED COMPONENTS AND TEST MODULES WILL BE REMOVED"
            loggingUtils.echoBanner(msg)

            deploymentUtils.removeComponents(projectInfo, projectInfo.components + projectInfo.testModules)
        }
        else {
            echo "CLEANING NAMESPACE NOT SELECTED; SKIPPING..."
        }
    }
 
    buildSelectedModules(projectInfo.selectedArtifacts, 'Artifacts')
    
    buildSelectedModules(projectInfo.selectedComponents, 'Components')
    
    buildSelectedModules(projectInfo.selectedTestModules, 'Test Modules')
}
