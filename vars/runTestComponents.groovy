/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select environment, module(s), and branch") {
        modulesUtils.getSelectedTestModules(projectInfo, args)
    }
    
    runTestModulesUtils.runSelectedModulePipelines(projectInfo, projectInfo.selectedTestComponents, 'Test Modules')
}
