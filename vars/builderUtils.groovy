/*
 * Basic build utilites and stages
 *
 * @see the build-to-dev pipeline for example on how to use
 */

def buildTestAndScan(def projectInfo) {
    projectInfo.microServices.each { microService ->
        if (microService.gitBranch) { 
            stage("build step: build ${microService.name}") {
                pipelineUtils.echoBanner("BUILD MICROSERVICE: ${microService.name}")
        
                dir(microService.workDir) {
                    def builderModule = load(microService[el.cicd.BUILDER] ?: el.cicd.BUILDER)
                    builderModule.build(projectInfo.id, microService.name)
                }
            }
        
            stage("build step: run unit tests for ${microService.name}") {
                pipelineUtils.echoBanner("RUN UNIT TESTS: ${microService.name}")
        
                dir(microService.workDir) {
                    def testerModule = load(microService[el.cicd.TESTER] ?: el.cicd.TESTER)
                    testerModule.test(projectInfo.id, microService.name)
                }
            }
        
            stage("build step: source code analysis for ${microService.name}") {
                pipelineUtils.echoBanner("SCAN CODE: ${microService.name}")
        
                dir(microService.workDir) {
                    def scannerModule = load(microService[el.cicd.SCANNER] ?: el.cicd.SCANNER)
                    scannerModule.scan(projectInfo.id, microService.name)
                }
            }
        }
    }
}