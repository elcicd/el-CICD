/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select environment, module(s), and branch") {
        moduleUtils.getSelectedTestModules(projectInfo, args)
    }
    
    moduleUtils.runSelectedModulePipelines(projectInfo, projectInfo.selectedTestComponents, 'Test Modules')
}
