/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Run system tests for all components per codeBase.
 */

def call(Map args) {
    assert args.projectInfo
    assert args.testModulesToRun
    assert args.componentsToTest

    def projectInfo = args.projectInfo
    def testModulesToRun = args.testModulesToRun
    def componentsToTest = args.componentsToTest

    def repoToMsMap = componentsToTest.collectEntries {
        [it.gitRepoName, it]
    }

    def codeBasesToNodes = [:]
    testModulesToRun.each { testModule ->
        def msCodeBaseList = []
        testModule.componentRepos.each { gitRepoName ->
            if (repoToMsMap[gitRepoName]) {
                msCodeBaseList.add(repoToMsMap[gitRepoName])
            }
        }

        if (msCodeBaseList) {
            codeBasesToNodes[testModule.codeBase] = createTestNode(testModule.codeBase, projectInfo, testModule, msCodeBaseList)
        }
    
        loggingUtils.echoBanner("CLONING SYSTEM TEST REPO: ${testModule.gitRepoName}")
    }

    parallel(codeBasesToNodes)
}

def createTestNode(def codeBase, def projectInfo, def testModule, def componentsToTest) {
    return {
        podTemplate([
            label: "${codeBase}",
            cloud: 'openshift',
            serviceAccount: "${el.cicd.JENKINS_TESTER_SERVICE_ACCOUNT}",
            podRetention: onFailure(),
            idleMinutes: "${el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
            namespace: "${projectInfo.testModuleNamespace}",
            containers: [
                containerTemplate(
                    name: 'jnlp',
                    image: "${el.cicd.JENKINS_OCI_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${codeBase}:latest",
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
                            git url: el.cicd.EL_CICD_CONFIG_GIT_REPO,
                                branch: el.cicd.EL_CICD_CONFIG_GIT_REPO_BRANCH_NAME,
                                credentialsId: el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
                        }

                    }

                    stage ('Pull test code') {
                        loggingUtils.echoBanner("CLONING SYSTEM TEST REPO: ${testModule.gitRepoName}")
                        dir(testModule.workDir) {
                            git url: testModule.gitRepoUrl,
                                branch: projectInfo.gitTestBranch,
                                credentialsId: testModule.gitDeployKeyJenkinsId
                        }
                    }

                    stage ('Run tests') {
                        def testModule = load "${el.cicd.SYSTEM_TEST_RUNNERS_DIR}/${codeBase}.groovy"
                        componentsToTest.each { component ->
                            loggingUtils.echoBanner("TESTING ${component.name} IN ${projectInfo.testModuleNamespace} WITH ${codeBase}")
                            dir(testModule.workDir) {
                                testModule.runTests(projectInfo, component, projectInfo.testModuleNamespace, projectInfo.testModuleEnv)
                            }
                        }
                    }
                }
                catch (Exception exception) {
                    loggingUtils.echoBanner("!!!! TEST(S) FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}")

                    throw exception
                }
            }

            loggingUtils.echoBanner("SYSTEM TESTS SUCCEEDED")
        }
    }
}
