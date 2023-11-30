

def getSelectedModules(def projectInfo) {  
    def DEPLOY_TO_NAMESPACE = 'Deploy to Namespace'
    def GIT_BRANCH = 'Git branch?'
    def CLEAN_NAMESPACE = 'Clean namespace?'
    
    def cnDesc = 'Uninstall all components currently deployed in selected namespace before deploying new builds'
    List inputs = [choice(name: DEPLOY_TO_NAMESPACE, description: 'The namespace to build and deploy to'),
                    stringParam(name: GIT_BRANCH, defaultValue: projectInfo.gitBranch, description: 'Branch to build?'),
                    booleanParam(name: RECREATE_NAMESPACE, description: cnDesc)]
    
    def BUILD_ALL_ARTIFACTS = 'Build all artifacts'
    def BUILD_ALL_COMPONENTS = 'Build and deploy all components'
    def BUILD_ALL_TEST_MODULES = 'Build and deploy all test modules'
    
    inputs += booleanParam(name: BUILD_ALL_ARTIFACTS)
    inputs += booleanParam(name: BUILD_ALL_COMPONENTS)
    inputs += booleanParam(name: BUILD_ALL_TEST_MODULES)
    
    createModuleInputs(projectInfo, projectInfo.artifacts, BUILD_ALL_ARTIFACTS, 'Artifacts', inputs)    
    createModuleInputs(projectInfo, projectInfo.components, BUILD_ALL_COMPONENTS, 'Components', inputs)    
    createModuleInputs(projectInfo, projectInfo.testModules, BUILD_ALL_TEST_MODULES, 'Test Modules', inputs)

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", args, inputs)

    projectInfo.deployToNamespace = cicdInfo[(DEPLOY_TO_NAMESPACE)]
    projectInfo.gitBranch = cicdInfo[(GIT_BRANCH)]
    projectInfo.cleanNamespace = cicdInfo[(CLEAN_NAMESPACE)]
    
    projectInfo.selectedArtifacts = collectModules(cicdInfo, projectInfo.artifacts, BUILD_ALL_ARTIFACTS)
    projectInfo.selectedComponents = collectModules(cicdInfo, projectInfo.components, BUILD_ALL_COMPONENTS)
    projectInfo.selectedTestModules = collectModules(cicdInfo, projectInfo.testModules, BUILD_ALL_TEST_MODULES)
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

def createModuleInputs(def projectInfo, def modules, def allTitle, def moduleName, def inputs) {
    inputs += modules.collect { module ->
        booleanParam(name: artifact.name, description: "${moduleTitle} status: ${artifact.status}")
    }
}

def collectModules(def cicdInfo, def modules, def collectAllKey) {
    return modules.collect { module ->
        cicdInfo[(collectAllKey) || (cicdInfo[(module.name)])
    }
}
