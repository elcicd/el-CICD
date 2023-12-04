/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = args.testEnv

    stage("Select environment, module(s), and branch") {
        moduleUtils.getSelectedTestModules(projectInfo, args)
    }
    
    def params = [string(name: 'TEST_ENV', value: projectInfo.deployToEnv), string(name: 'GIT_BRANCH', value: projectInfo.gitBranch)]
    moduleUtils.runSelectedModulePipelines(projectInfo, projectInfo.selectedTestComponents, 'Test Modules', params)
}
