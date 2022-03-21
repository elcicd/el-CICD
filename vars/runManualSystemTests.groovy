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
        def allEnvs = "${projectInfo.sandboxNamespaces.join('\n')}\n${projectInfo.devEnv}\n${projectInfo.testEnvs.join('\n')}\n${projectInfo.preProdEnv}"
        def inputs = [choice(name: 'testEnv', description: '', choices: allEnvs)]

        def cicdInfo = input(message: "Select environment test microservices in:", parameters: inputs)

        projectInfo.systemTestEnv = cicdInfo
        projectInfo.SYSTEM_TEST_ENV = projectInfo.systemTestEnv.toUpperCase()
        projectInfo.systemTestNamespace = projectInfo.nonProdNamespaces[projectInfo.systemTestEnv]
    }

    stage ('Select microservices to test') {
        pipelineUtils.echoBanner("SELECT WHICH MICROSERVICE TESTS TO RUN")

        def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.microservice}{":"}{.data.deployment-branch}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.deployToNamespace}"
        def msNameDepBranch = sh(returnStdout: true, script: script).split(' ')

        def inputs = []
        projectInfo.microServices.each { microService ->
            def branchPrefix = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.deployToEnv}-"
            def msToBranch = msNameDepBranch.find { it.startsWith("${microService.name}:${branchPrefix}") }

            if (msToBranch) {
                microService.deploymentBranch = msToBranch ? msToBranch.split(':')[1] : ''
                microService.deploymentImageTag = microService.deploymentBranch.replaceAll("${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')

                inputs += booleanParam(name: microService.name)
            }
        }

        if (!inputs) {
            pipelineUtils.errorBanner("NO MICROSERVICES AVAILABLE FOR TESTING IN ${projectInfo.deployToEnv}")
        }

        def cicdInfo = input(message: "Select microservices to test in ${projectInfo.deployToEnv}",
                             parameters: inputs)

        projectInfo.microServicesToTest = projectInfo.microServices.findAll { microService ->
            microService.runTests = (inputs.size() > 1) ? cicdInfo[microService.name] : cicdInfo

            if (microService.runTests) {
                microService.deploymentImageTag = (answer =~ "${projectInfo.deployToEnv}-[0-9a-z]{7}")[0]
                microService.srcCommitHash = microService.deploymentImageTag.split('-')[1]
                microService.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${microService.deploymentImageTag}"
            }

            return microService.runTests
        }

        if (!projectInfo.microServicesToTest) {
            pipelineUtils.errorBanner("NO MICROSERVICES SELECTED FOR TESTING IN ${projectInfo.deployToEnv}")
        }
    }

    runSystemTests(projectInfo: projectInfo, microServicesToTest: projectInfo.microServicesToTest)
}
