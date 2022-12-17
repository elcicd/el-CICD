/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-and-deploy-components pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-and-deploy-components-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select the environment and test types to run') {
        def allEnvs = "${projectInfo.nonProdEnvs.join('\n')}\n${projectInfo.sandboxEnvs.join('\n')}"
        def TEST_ENV = 'Test Environment:'
        def TEST_CODE_BASE = 'Test Type:'
        def inputs = [choice(name: TEST_ENV, description: '', choices: allEnvs)]

        inputs.addAll(projectInfo.testModules.collect { booleanParam(name: "${it.scmRepoName}/${it.codeBase}") } )

        def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select environment and test types to run:", inputs)

        projectInfo.testModuleEnv = cicdInfo[TEST_ENV]
        projectInfo.SYSTEM_TEST_ENV = projectInfo.testModuleEnv.toUpperCase()
        projectInfo.testModuleNamespace = projectInfo.nonProdNamespaces[projectInfo.testModuleEnv]
        projectInfo.testModuleNamespace = projectInfo.testModuleNamespace ?: projectInfo.sandboxNamespaces[projectInfo.testModuleEnv]

        projectInfo.testModulesToRun = [] as Set
        cicdInfo.each { name, answer ->
            if (name != TEST_ENV && answer) {
                def testModule = projectInfo.testModules.find { "${it.scmRepoName}/${it.codeBase}" == name }
                if (testModule) {
                    projectInfo.testModulesToRun += testModule
                }
            }
        }

        if (!projectInfo.testModulesToRun) {
            loggingUtils.errorBanner("NO TEST TYPES SELECTED TO RUN")
        }
    }

    stage ('Select components to test') {
        def jsonPath = '{range .items[?(@.data.component)]}{.data.component}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.testModuleNamespace}"
        def msNames = sh(returnStdout: true, script: script).split(' ')

        def testMicroServiceReposSet = [] as Set
        projectInfo.testModulesToRun.each { testModule ->
            testMicroServiceReposSet.addAll(testModule.componentRepos)
        }

        def inputs = []
        def msTestPossibilities = [] as Set
        projectInfo.components.each { component ->
            if (msNames.contains(component.name) && testMicroServiceReposSet.contains(component.scmRepoName)) {
                msTestPossibilities += component
                inputs += booleanParam(name: component.name)
            }
        }

        def TEST_ALL = "Test all"
        if (inputs.size() > 1) {
            inputs.add(0, booleanParam(name: TEST_ALL))
        }

        if (!inputs) {
            loggingUtils.errorBanner("NO COMPONENTS AVAILABLE FOR TESTING IN ${projectInfo.testModuleEnv}")
        }

        def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select components to test in ${projectInfo.testModuleEnv}", inputs)

        projectInfo.componentsToTest = msTestPossibilities.findAll { component ->
            return (inputs.size() > 1) ? (cicdInfo[TEST_ALL] || cicdInfo[component.name]) : cicdInfo
        }

        projectInfo.testModulesToRun = [] as Set
        projectInfo.componentsToTest.each { component ->
            def sts = projectInfo.testModules.findAll { testModule -> 
                testModule.componentRepos.find { it == component.scmRepoName }
            }
            projectInfo.testModulesToRun.addAll(sts)
        }

        if (!projectInfo.componentsToTest) {
            loggingUtils.errorBanner("NO COMPONENTS SELECTED FOR TESTING IN ${projectInfo.testModuleEnv}")
        }
    }

    runPostDeploymentTests(projectInfo: projectInfo, testModulesToRun: projectInfo.testModulesToRun, componentsToTest: projectInfo.componentsToTest)
}
