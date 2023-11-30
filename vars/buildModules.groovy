/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select environment, module(s), and branch") {
        buildModulesUtils.getSelectedModules(projectInfo)
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
 
    buildModulesUtils.buildSelectedModules(projectInfo.selectedArtifacts, 'Artifacts')
    
    buildModulesUtils.buildSelectedModules(projectInfo.selectedComponents, 'Components')
    
    buildModulesUtils.buildSelectedModules(projectInfo.selectedTestModules, 'Test Modules')
}
