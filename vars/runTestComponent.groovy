/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def module = projectInfo.modules.find { it.name == args.moduleName }

    module.gitBranch = args.gitBranch

    projectInfo.deployToEnv = projectInfo.testEnv
    projectInfo.deployToNamespace = projectInfo.nonProdNamespaces(projectInfo.testEnv) ?: projectInfo.sandboxNamespaces(projectInfo.testEnv)

    stage('Checkout code from repository') {
        moduleUtils.cloneModule(module)
    }
    
    [el.cicd.BUILDER, el.cicd.TESTER].each { buildStep ->
        stage("Test step: ${buildStep.toUpperCase()}") {
            moduleUtils.runBuildStep(projectInfo, module, buildStep, 'TEST MODULE')
        }
    }
}
