/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select environment, module(s), and branch") {
        moduleUtils.getSelectedModules(projectInfo, args)
    }

    stage("Clean ${projectInfo.deployToNamespace}") {
        if (projectInfo.cleanNamespace) {
            def msg = "CLEANING NAMESPACE ${projectInfo.deployToNamespace}: ALL DEPLOYED COMPONENTS AND TEST MODULES WILL BE REMOVED"
            loggingUtils.echoBanner(msg)

            deployComponentsUtils.removeComponents(projectInfo, projectInfo.components)
        }
        else {
            echo "CLEANING NAMESPACE NOT SELECTED: SKIPPING..."
        }
    }
 
    moduleUtils.runSelectedModulePipelines(projectInfo, projectInfo.selectedArtifacts, 'Artifacts')
    
    moduleUtils.runSelectedModulePipelines(projectInfo, projectInfo.selectedComponents, 'Components')
}