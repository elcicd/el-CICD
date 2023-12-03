/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def getSelectedModules(def projectInfo, def args) {
    def DEPLOY_TO_NAMESPACE = 'Deploy to Namespace'
    def GIT_BRANCH = 'Git branch?'
    def CLEAN_NAMESPACE = 'Clean namespace?'

    def cnDesc = 'Uninstall all components currently deployed in selected namespace before deploying new builds'
    List inputs = [choice(name: DEPLOY_TO_NAMESPACE,
                          description: 'The namespace to build and deploy to',
                          choices: projectInfo.buildNamespaces),
                   stringParam(name: GIT_BRANCH, defaultValue: projectInfo.gitBranch, description: 'Branch to build?'),
                   booleanParam(name: CLEAN_NAMESPACE, description: cnDesc)]

    def BUILD_ALL_ARTIFACTS = 'Build all artifacts'
    def BUILD_ALL_COMPONENTS = 'Build and deploy all components'

    inputs += booleanParam(name: BUILD_ALL_ARTIFACTS)
    inputs += booleanParam(name: BUILD_ALL_COMPONENTS)

    inputs += separator(name: 'ARTIFACTS', sectionHeader: 'ARTIFACTS')
    createModuleInputs(inputs, projectInfo, projectInfo.artifacts, 'Artifact')

    inputs += separator(name: 'COMPONENTS', sectionHeader: 'COMPONENTS')
    createModuleInputs(inputs, projectInfo, projectInfo.components, 'Component')

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", args, inputs)

    projectInfo.deployToNamespace = cicdInfo[DEPLOY_TO_NAMESPACE]
    projectInfo.gitBranch = cicdInfo[GIT_BRANCH]
    projectInfo.cleanNamespace = cicdInfo[CLEAN_NAMESPACE]

    projectInfo.selectedArtifacts = projectInfo.artifacts.findAll { cicdInfo[BUILD_ALL_ARTIFACTS] || cicdInfo[it.name] }
    projectInfo.selectedComponents = projectInfo.components.findAll { cicdInfo[BUILD_ALL_COMPONENTS] || cicdInfo[it.name] }
}

def buildSelectedModules(def projectInfo, def modules, def title) {
    loggingUtils.echoBanner("BUILDING SELECTED ${title.toUpperCase()}")

    def buildStages =  concurrentUtils.createParallelStages("Build ${title}", modules) { module ->
        echo "--> Building ${module.name}"

        pipelineSuffix = projectInfo.selectedArtifacts.contains(module) ? 'build-artifact' : 'build-component'
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
