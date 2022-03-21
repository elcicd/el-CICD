/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-and-deploy-microservices pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-and-deploy-microservices-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select the environment to run tests in') {
        def allEnvs = "${projectInfo.sandboxEnvs.join('\n')}\n${projectInfo.devEnv}\n${projectInfo.testEnvs.join('\n')}\n${projectInfo.preProdEnv}"
        def inputs = [choice(name: 'testEnv', description: '', choices: allEnvs)]

        def cicdInfo = input(message: "Select environment test microservices in:", parameters: inputs)

        projectInfo.systemTestEnv = cicdInfo
        projectInfo.SYSTEM_TEST_ENV = projectInfo.systemTestEnv.toUpperCase()
        projectInfo.systemTestNamespace = projectInfo.nonProdNamespaces[projectInfo.systemTestEnv]
        projectInfo.systemTestNamespace = projectInfo.systemTestNamespace ?: projectInfo.sandboxNamespaces[projectInfo.systemTestEnv]
    }

    stage ('Select microservices to test') {
        pipelineUtils.echoBanner("SELECT WHICH MICROSERVICE TESTS TO RUN")

        def jsonPath = '{range .items[?(@.data.microservice)]}{.data.microservice}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.systemTestNamespace}"
        def msNames = sh(returnStdout: true, script: script).split(' ')

        def inputs = []
        projectInfo.microServices.each { microService ->
            if (msNames.contains(microService.name)) {
                inputs += booleanParam(name: microService.name)
            }
        }

        if (!inputs) {
            pipelineUtils.errorBanner("NO MICROSERVICES AVAILABLE FOR TESTING IN ${projectInfo.systemTestEnv}")
        }

        def cicdInfo = input(message: "Select microservices to test in ${projectInfo.systemTestEnv}",
                             parameters: inputs)

        projectInfo.microServicesToTest = projectInfo.microServices.findAll { microService ->
            return (inputs.size() > 1) ? cicdInfo[microService.name] : cicdInfo
        }

        if (!projectInfo.microServicesToTest) {
            pipelineUtils.errorBanner("NO MICROSERVICES SELECTED FOR TESTING IN ${projectInfo.systemTestEnv}")
        }
    }

    runSystemTests(projectInfo: projectInfo, microServicesToTest: projectInfo.microServicesToTest)
}
