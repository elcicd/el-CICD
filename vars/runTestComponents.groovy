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
    
    def params = [choiceParam(TEST_ENV: projectInfo.deployToEnv), stringParam(GIT_BRANCH: projectInfo.gitBranch)]
    moduleUtils.runSelectedModulePipelines(projectInfo, projectInfo.selectedTestComponents, 'Test Modules', params)
}
