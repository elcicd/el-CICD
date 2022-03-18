/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Run system tests for all microservices per codeBase.
 */

def call(Map args) {
    assert args.projectInfo
    assert args.microServicesToTest

    def projectInfo = args.projectInfo

    def codeBases = microServicesToTest.unique { microServiceA, microServiceB ->
        return microServiceA.systemTests.codeBase <=> microServiceA.systemTests.codeBase
    }

    def codeBasesToNodes = [:]
    codeBases.each { codeBase ->
        codeBaseMicroServicesToTest = microServicesToTest.collect { microService ->
            microService.codeBase == codeBase
        }
        codeBasesToNodes[codeBase] = createTestNode(codeBase, projectInfo, codeBaseMicroServicesToTest)
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
                    image: "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${args.agent}:latest",
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
                        pipelineUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR MICROSERVICE: ${microService.name}")
                    }

                    stage ('Run tests') {
                        pipelineUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR MICROSERVICE: ${microService.name}")
                        def testModule = load "${el.cicd.BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
                        builderModule.build(projectInfo, microService)
                    }
                }
                catch (Exception | AssertionError exception) {
                    (exception instanceof Exception) ?
                        pipelineUtils.echoBanner("!!!! JOB FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}") :
                        pipelineUtils.echoBanner("!!!! JOB ASSERTION FAILED !!!!", "", "ASSERTION: ${exception}")

                    runHookScript(el.cicd.ON_FAIL, args, exception)

                    throw exception
                }
                finally {
                    runHookScript(el.cicd.POST, args)
                }
            }
        }
    }
}
