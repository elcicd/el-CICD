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

    def codeBasesToNodes = [:]
    codeBasesToMicroServices.each { codeBase, codeBaseMicroServicesToTest ->
        codeBasesToNodes[codebase] = createTestNode(codeBase, projectInfo, codeBaseMicroServicesToTest)
    }

    parallel(codeBasesToNodes)
}

def createTestNode(def codeBase, def projectInfo, def microServicesToTest) {
    return {
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
                    stage('Initializing') {
                        sh """
                            rm -rf '${WORKSPACE}'
                            mkdir -p '${WORKSPACE}'
                        """

                        dir (el.cicd.CONFIG_DIR) {
                            git url: el.cicd.EL_CICD_CONFIG_REPOSITORY,
                                branch: el.cicd.EL_CICD_CONFIG_REPOSITORY_BRANCH_NAME,
                                credentialsId: el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
                        }

                    }

                    stage ('Pull test code') {
                        def msgs = ["CLONING SYSTEM TEST REPOS:"]
                        msgs.addAll(microServicesToTest.collect { "${it.systemTests.gitRepoName}:${projectInfo.gitTestBranch}" })
                        pipelineUtils.echoBanner(msgs)
                        microServicesToTest.each { microService ->
                            sh """
                                git clone --branch ${projectInfo.gitTestBranch} ${microService.systemTests.gitRepoUrl}
                            """
                        }
                    }

                    stage ('Run tests') {
                        def testModule = load "${el.cicd.SYSTEM_TEST_RUNNERS_DIR}/${codeBase}.groovy"
                        microServicesToTest.each { microService ->
                            pipelineUtils.echoBanner("TESTING ${microService.name}")
                            dir(microService.systemTests.workDir) {
                                testModule.runTests(projectInfo, microService, projectInfo.systemTestNamespace, projectInfo.systemTestEnv)
                            }
                        }
                    }
                }
                catch (Exception exception) {
                    pipelineUtils.echoBanner("!!!! TEST(S) FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}")

                    throw exception
                }
            }

            pipelineUtils.echoBanner("SYSTEM TESTS SUCCEEDED")
        }
    }
}
