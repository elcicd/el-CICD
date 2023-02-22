/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-and-deploy-components pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-and-deploy-components-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select sandbox, component, and branch") {
        def BUILD_SELECTED = 'Build Selected'
        def BUILD_ALL = 'Build All'
        def BUILD_ALL_COMPONENTS = 'Build All Components'
        def BUILD_ALL_ARTIFACTS = 'Build All Artifacts'
        def buildChoices = [BUILD_SELECTED, BUILD_ALL, BUILD_ALL_COMPONENTS, BUILD_ALL_ARTIFACTS]
        List inputs = [choice(name: 'buildToNamespace', description: 'The namespace to build and deploy to', choices: projectInfo.builderNamespaces),
                       choice(name: 'buildChoice',
                              description: 'Choose to build selected components, everything, all components, or all artifacts',
                              choices: buildChoices),
                       booleanParam(name: 'cleanNamespace',
                                    description: 'Uninstall all components currently deployed in selected namespace before deploying new builds')]

        inputs += projectInfo.buildModules.collect { module ->
            def moduleType = module.isComponent ? 'Component' : 'Artifact'
            booleanParam(name: module.name, description: "${moduleType} status: ${module.status}")
        }

        def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", inputs)

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

    stage('Uninstall all components, if selected') {
        if (projectInfo.cleanNamespace) {
            def removalStages = deploymentUtils.createComponentRemovalStages(projectInfo, projectInfo.components)
            parallel(removalStages)
        }
        else {
            echo "REINSTALL NOT SELECTED: COMPONENTS ALREADY DEPLOYED WILL BE UPGRADED"
        }
    }

    stage("Building selected modules") {
        def buildModules = buildModules.findAll { it.build }

        if (buildModules) {
            def buildStages = createBuildStages(projectInfo, buildModules)
            parallel(createBuildStages)
        }
        else {
            echo "No modules selected for building"
        }
    }
}

def createBuildStages(def projectInfo, def buildModules) {
    def buildStages = [:]
    for (i in 0..< el.cicd.JENKINS_MAX_STAGES) {
        def stageName = "parallel build stage ${i}"
        buildStages[stageName] = {
            stage(stageName) {
                while (buildModules) {
                    def buildModule = projectUtils.synchronizedRemoveListItem(buildModules)
                    loggingUtils.echoBanner("BUILDING ${buildModule.name}")

                    pipelineSuffix = projectInfo.components.contains(module) ? 'build-component' : 'build-artifact'
                    build(job: "../${projectInfo.id}/${module.name}-${pipelineSuffix}", wait: true)

                    loggingUtils.echoBanner("${buildModule.name} BUILT AND DEPLOYED SUCCESSFULLY")
                }
            }
        }
    }

    return buildStages
}
