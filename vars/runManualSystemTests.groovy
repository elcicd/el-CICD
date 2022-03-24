/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-and-deploy-microservices pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-and-deploy-microservices-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select the environment and test types to run') {
        def allEnvs = "${projectInfo.sandboxEnvs.join('\n')}\n${projectInfo.devEnv}\n${projectInfo.testEnvs.join('\n')}\n${projectInfo.preProdEnv}"
        def TEST_ENV = 'Test Environment:'
        def TEST_CODE_BASE = 'Test Type:'
        def inputs = [choice(name: TEST_ENV, description: '', choices: allEnvs)]

        inputs.addAll(projectInfo.systemTests.collect { booleanParam(name: "${it.gitRepoName}/${it.codeBase}") } )

        def cicdInfo = input(message: "Select environment and test types to run:", parameters: inputs)

        projectInfo.systemTestEnv = cicdInfo[TEST_ENV]
        projectInfo.SYSTEM_TEST_ENV = projectInfo.systemTestEnv.toUpperCase()
        projectInfo.systemTestNamespace = projectInfo.nonProdNamespaces[projectInfo.systemTestEnv]
        projectInfo.systemTestNamespace = projectInfo.systemTestNamespace ?: projectInfo.sandboxNamespaces[projectInfo.systemTestEnv]

        projectInfo.systemTestsToRun = [] as Set
        cicdInfo.each { name, answer ->
            if (name != TEST_ENV) {
                def systemTest = projectInfo.systemTests.find { "${it.gitRepoName}/${it.codeBase}" == name }
                if (systemTest) {
                    projectInfo.systemTestsToRun += systemTest
                }
            }
        }

        if (!projectInfo.systemTestsToRun) {
            pipelineUtils.errorBanner("NO TEST TYPES SELECTED TO RUN")
        }
    }

    stage ('Select microservices to test') {
        def jsonPath = '{range .items[?(@.data.microservice)]}{.data.microservice}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.systemTestNamespace}"
        def msNames = sh(returnStdout: true, script: script).split(' ')

        def testMicroServiceReposSet = [] as Set
        projectInfo.systemTestsToRun.each { systemTest ->
            testMicroServiceReposSet.addAll(systemTest.microServiceRepos)
        }

        echo "testMicroServiceReposSet: ${testMicroServiceReposSet}"
        echo "msNames: ${msNames}"
        def inputs = []
        projectInfo.microServices.each { microService ->
            if (msNames.contains(microService.name) && testMicroServiceReposSet.contains(microService.gitRepoName)) {
                inputs += booleanParam(name: microService.name)
            }
        }

        def TEST_ALL = "Test all"
        if (inputs.size() > 1) {
            inputs.add(0, booleanParam(name: TEST_ALL))
        }

        if (!inputs) {
            pipelineUtils.errorBanner("NO MICROSERVICES AVAILABLE FOR TESTING IN ${projectInfo.systemTestEnv}")
        }

        def cicdInfo = input(message: "Select microservices to test in ${projectInfo.systemTestEnv}",
                             parameters: inputs)

        projectInfo.microServicesToTest = projectInfo.microServices.findAll { microService ->
            return cicdInfo[TEST_ALL] || cicdInfo[microService.name]
        }

        if (!projectInfo.microServicesToTest) {
            pipelineUtils.errorBanner("NO MICROSERVICES SELECTED FOR TESTING IN ${projectInfo.systemTestEnv}")
        }
    }

    runSystemTests(projectInfo: projectInfo, systemTestsToRun: projectInfo.systemTestsToRun, microServicesToTest: projectInfo.microServicesToTest)
}
