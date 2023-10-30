/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-artifacts-and-components pipeline.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select sandbox, component, and branch") {
        def BUILD_SELECTED = 'Build and Deploy Selected'
        def BUILD_ALL = 'Build and Deploy All'
        def BUILD_ALL_COMPONENTS = 'Build and Deploy All Components'
        def BUILD_ALL_ARTIFACTS = 'Build All Artifacts'
        def buildChoices = [BUILD_SELECTED, BUILD_ALL, BUILD_ALL_COMPONENTS, BUILD_ALL_ARTIFACTS]
        List inputs = [choice(name: 'buildToNamespace', description: 'The namespace to build and deploy to', choices: projectInfo.buildNamespaces),
                       choice(name: 'buildChoice',
                              description: 'Choose to build selected components, everything, all components, or all artifacts',
                              choices: buildChoices),
                       booleanParam(name: 'cleanNamespace',
                                    description: 'Uninstall all components currently deployed in selected namespace before deploying new builds')]

        inputs += projectInfo.buildModules.collect { module ->
            def moduleType = module.isComponent ? 'Component' : 'Artifact'
            booleanParam(name: module.name, description: "${moduleType} status: ${module.status}")
        }

        def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", args, inputs)

        projectInfo.deployToNamespace = cicdInfo.buildToNamespace
        projectInfo.scmBranch = cicdInfo.scmBranch
        projectInfo.cleanNamespace = cicdInfo.cleanNamespace
        projectInfo.buildModules.each { module ->
            module.build =
                (cicdInfo.buildChoice == BUILD_ALL) ||
                (cicdInfo.buildChoice == BUILD_ALL_COMPONENTS && module.isComponent) ||
                (cicdInfo.buildChoice == BUILD_ALL_ARTIFACTS && module.isArtifact) ||
                (cicdInfo.buildChoice == BUILD_SELECTED && cicdInfo[module.name])
        }
    }

    stage("Clean ${projectInfo.deployToNamespace}") {
        if (projectInfo.cleanNamespace) {
            loggingUtils.echoBanner("CLEANING NAMESPACE ${projectInfo.deployToNamespace}: ALL DEPLOYED COMPONENTS WILL BE REMOVED")

            deploymentUtils.removeComponents(projectInfo, projectInfo.components)
        }
        else {
            echo "CLEANING NAMESPACE NOT SELECTED; SKIPPING..."
        }
    }

    stage("Building selected modules") {
        def buildModules = projectInfo.buildModules.findAll { it.build }

        if (buildModules) {
            def buildStages =  concurrentUtils.createParallelStages("Build Module", buildModules) { module ->
                loggingUtils.echoBanner("BUILDING ${module.name}")

                pipelineSuffix = projectInfo.components.contains(module) ? 'build-component' : 'build-artifact'
                build(job: "${module.name}-${pipelineSuffix}", wait: true)

                loggingUtils.echoBanner("${module.name} BUILT AND DEPLOYED SUCCESSFULLY")
            }
            
            parallel(buildStages)
        }
        else {
            echo "NO MODULES SELECTED FOR BUILDING: SKIPPING"
        }
    }
}
