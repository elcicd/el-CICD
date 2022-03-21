/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Run system tests for all microservices per codeBase.
 */

def call(Map args) {
    assert args.projectInfo
    assert args.microServicesToTest

    def projectInfo = args.projectInfo
    def microServicesToTest = args.microServicesToTest

    def codeBasesToMicroServices = [:]
    microServicesToTest.each { microService ->
        codeBasesToMicroServices[microService.systemTests.codeBase] = codeBasesToMicroServices[microService.systemTests.codeBase] ?: []
        codeBasesToMicroServices[microService.systemTests.codeBase].add(microService)
    }

    echo '!!!!!!!!!!!!!!!!!!! HOWDY !!!!!!!!!!!!!!!!!!!!'

    codeBasesToMicroServices.each { codeBase, codeBaseMicroServicesToTest->
        createTestNode(codeBase, projectInfo, codeBaseMicroServicesToTest)
    }

    echo '!!!!!!!!!!!!!!!!!!! HOWDY END !!!!!!!!!!!!!!!!!!!!'

    // parallel(codeBasesToNodes)
}

def createTestNode(def codeBase, def projectInfo, def microServicesToTest) {
    //return {
        podTemplate([
            label: "${codeBase}",
            cloud: 'openshift',
            serviceAccount: 'jenkins',
            podRetention: onFailure(),
            idleMinutes: "${el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
            namespace: "${projectInfo.systemTestNamespace}",
            containers: [
                containerTemplate(
                    name: 'jnlp',
                    image: "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${codeBase}:latest",
                    alwaysPullImage: true,
                    args: '${computer.jnlpmac} ${computer.name}',
                    resourceRequestMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
                    resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
                    resourceRequestCpu: "${el.cicd.JENKINS_AGENT_CPU_REQUEST}",
                    resourceLimitCpu: "${el.cicd.JENKINS_AGENT_CPU_LIMIT}"
                )
            ]
        ]) {
            node(codeBase) {
                try {
                    stage ('Pull test code') {
                        def msgs = ["CLONING SYSTEM TEST REPOS:"] + 
                                   microServicesToTest.collect { "${it.systemTests.gitRepoName}:${projectInfo.gitTestBranch}" }.unique()
                        pipelineUtils.echoBanner(msgs)

                        microServicesToTest.each { micrmicroServiceToTestoService ->
                            dir(microService.testWorkDir) {
                                pipelineUtils.cloneGitRepo(microService.systemTests.gitRepoName, projectInfo.gitTestBranch)
                            }
                        }
                    }

                    stage ('Run tests') {
                        def testModule = load "${el.cicd.SYSTEM_TEST_RUNNERS_DIR}/${codeBase}.groovy"
                        microServicesToTest.each { microService ->
                            pipelineUtils.echoBanner("TESTING ${microService.name}")
                            dir(microService.testWorkDir) {
                                testModule.runTests(projectInfo, microService, projectInfo.systemTestNamespace, projectInfo.systemTestEnv)
                            }
                        }
                    }
                }
                catch (Exception exception) {
                    pipelineUtils.errorBanner("!!!! TEST(S) FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}")
                }
            }

            pipelineUtils.echoBanner("SYSTEM TESTS SUCCEEDED")
        }
    //}
}
