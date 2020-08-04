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
                    def moduleName = microService[el.cicd.BUILDER] ?: el.cicd.BUILDER
                    def builderModule = load "${el.cicd.EL_CICD_BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
                    builderModule.build(projectInfo.id, microService.name)
                }
            }
        
            stage("build step: run unit tests for ${microService.name}") {
                pipelineUtils.echoBanner("RUN UNIT TESTS: ${microService.name}")
        
                dir(microService.workDir) {
                    def moduleName = microService[el.cicd.TESTER] ?: el.cicd.TESTER
                    def testerModule = load "${el.cicd.EL_CICD_BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
                    testerModule.test(projectInfo.id, microService.name)
                }
            }
        
            stage("build step: source code analysis for ${microService.name}") {
                pipelineUtils.echoBanner("SCAN CODE: ${microService.name}")
        
                dir(microService.workDir) {
                    def moduleName = microService[el.cicd.SCANNER] ?: el.cicd.SCANNER
                    def scannerModule = load "${el.cicd.EL_CICD_BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
                    scannerModule.scan(projectInfo.id, microService.name)
                }
            }
        }
    }
}