

def getSelectedModules(def projectInfo, def args) {  
    def DEPLOY_TO_NAMESPACE = 'Deploy to Namespace'
    def GIT_BRANCH = 'Git branch?'
    def CLEAN_NAMESPACE = 'Clean namespace?'
    
    def cnDesc = 'Uninstall all components currently deployed in selected namespace before deploying new builds'
    List inputs = [choice(name: DEPLOY_TO_NAMESPACE, description: 'The namespace to build and deploy to'),
                   stringParam(name: GIT_BRANCH, defaultValue: projectInfo.gitBranch, description: 'Branch to build?'),
                   booleanParam(name: CLEAN_NAMESPACE, description: cnDesc)]
    
    def BUILD_ALL_ARTIFACTS = 'Build all artifacts'
    def BUILD_ALL_COMPONENTS = 'Build and deploy all components'
    def BUILD_ALL_TEST_MODULES = 'Build and deploy all test modules'
    
    inputs += booleanParam(name: BUILD_ALL_ARTIFACTS)
    inputs += booleanParam(name: BUILD_ALL_COMPONENTS)
    inputs += booleanParam(name: BUILD_ALL_TEST_MODULES)
    
    inputs += separator(name: 'ARTIFACTS')
    createModuleInputs(inputs, projectInfo, projectInfo.artifacts, 'Artifact')
    createModuleInputs(inputs, projectInfo, projectInfo.components, 'Component')    
    createModuleInputs(inputs, projectInfo, projectInfo.testModules, 'Test Module')

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", args, inputs)

    projectInfo.deployToNamespace = cicdInfo[(DEPLOY_TO_NAMESPACE)]
    projectInfo.gitBranch = cicdInfo[(GIT_BRANCH)]
    projectInfo.cleanNamespace = cicdInfo[(CLEAN_NAMESPACE)]
    
    projectInfo.selectedArtifacts = projectInfo.artifacts.collect { cicdInfo[(BUILD_ALL_ARTIFACTS)] || cicdInfo[(it.name)] }
    projectInfo.selectedComponents = projectInfo.components.collect { cicdInfo[(BUILD_ALL_COMPONENTS)] || cicdInfo[(it.name)] }
    projectInfo.selectedTestModules = projectInfo.testModules.collect { cicdInfo[(BUILD_ALL_TEST_MODULES)] || cicdInfo[(it.name)] }
}

def buildSelectedModules(def modules, def title) {
    loggingUtils.echoBanner("BUILDING SELECTED ${title.toUpperCase()}")

    def buildStages =  concurrentUtils.createParallelStages("Build ${title}", buildModules) { module ->
        echo "--> Building ${module.name}"

        pipelineSuffix = projectInfo.selectedArtifacts.contains(module) ?
            'build-artifact' :
            (projectInfo.selectedComponents.contains(module) ? 'build-component' : 'build-test-module')
        build(job: "${module.name}-${pipelineSuffix}", wait: true)

        echo "--> ${module.name} build complete"
    }
    
    parallel(buildStages)
    
    if (modules) {
        loggingUtils.echoBanner("BUILDING SELECTED ${title.toUpperCase()} COMPLETE")
    }
    else {
        loggingUtils.echoBanner("NO ${title.toUpperCase()} SELECTED; SKIPPING")
    }
}

def createModuleInputs(def inputs, def projectInfo, def modules, def allTitle) {
    modules.each { module ->
        inputs.add(booleanParam(name: module.name, description: "Build ${module.name}?  Status: ${module.status}"))
    }
}
