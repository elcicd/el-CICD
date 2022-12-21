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
        List inputs = [choice(name: 'buildToNamespace', description: 'The Namespace to build and deploy to', choices: projectInfo.builderNamespaces),
                       choice(name: 'buildChoice', description: 'What to build', choices: buildChoices),
                       booleanParam(name: 'recreateAll', description: 'Delete everything from the environment before deploying')]

        inputs += projectInfo.buildModules.collect { module ->
            def moduleType = module.isComponent ? 'Component' : 'Artifact'
            booleanParam(name: module.name, description: "${moduleType} status: ${module.status}")
        }

        def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", inputs)

        projectInfo.deployToNamespace = cicdInfo.buildToNamespace
        projectInfo.scmBranch = cicdInfo.scmBranch
        projectInfo.recreateAll = cicdInfo.recreateAll
        projectInfo.buildModules.each { module ->
            module.build =
                (cicdInfo.buildChoice == BUILD_ALL) ||
                (cicdInfo.buildChoice == BUILD_ALL_COMPONENTS && module.isComponent) ||
                (cicdInfo.buildChoice == BUILD_ALL_ARTIFACTS && module.isArtifact) ||
                (cicdInfo.buildChoice == BUILD_SELECTED && cicdInfo[module.name])
        }
    }

    stage('Remove all components, if selected') {
        if (projectInfo.recreateAll) {
            def removalStages = deploymentUtils.createComponentRemovalStages(projectInfo, projectInfo.components)
            parallel(removalStages)
        }
        else {
            echo "RECREATE NOT SELECTED: COMPONENTS ALREADY DEPLOYED WILL BE UPGRADED"
        }
    }

    stage("Building selected modules") {
        def buildModules = [[],[],[]]
        projectInfo.buildModules.findAll { it.build }.eachWithIndex { module, i ->
            buildModules[i%3].add(module)
        }

        if (buildModules) {
            parallel(
                firstBucket: {
                    stage("building first bucket of components to ${projectInfo.deployToNamespace}") {
                        triggerPipelines(projectInfo, buildModules[0])
                    }
                },
                secondBucket: {
                    stage("building second bucket of components to ${projectInfo.deployToNamespace}") {
                        triggerPipelines(projectInfo, buildModules[1])
                    }
                },
                thirdBucket: {
                    stage("building third bucket of components to ${projectInfo.deployToNamespace}") {
                        triggerPipelines(projectInfo, buildModules[2])
                    }
                }
            )
        }
        else {
            echo "No modules selected for building"
        }
    }
}

def triggerPipelines(def projectInfo, def buildModules) {
    if (buildModules) {
        buildModules.each { module ->
            pipelineSuffix = projectInfo.components.contains(module) ? 'build-component' : 'build-artifact'
            build(job: "../${projectInfo.id}/${module.name}-${pipelineSuffix}", wait: true)
                                
            loggingUtils.echoBanner("${module.name} BUILT AND DEPLOYED SUCCESSFULLY")
        }
    }
}
