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
        List inputs = [choice(name: 'buildToNamespace', description: 'The Namespace to build and deploy to', choices: projectInfo.builderNamespaces),
                       string(name: 'scmBranch',  defaultValue: projectInfo.scmBranch, description: 'The branch to build', trim: true),
                       booleanParam(name: 'buildAll', description: 'Build all components'),
                       booleanParam(name: 'recreateAll', description: 'Delete everything from the environment before deploying')]

        inputs += projectInfo.components.collect { component ->
            booleanParam(name: component.name, description: "status: ${component.status}")
        }
        
        inputs += projectInfo.artifacts.collect { artifact ->
            booleanParam(name: artifact.name, description: "status: ${artifact.status}")
        }

        def cicdInfo = input(message: "Select artifacts and components to build:", parameters: inputs)

        projectInfo.deployToNamespace = cicdInfo.buildToNamespace
        projectInfo.scmBranch = cicdInfo.scmBranch
        projectInfo.recreateAll = cicdInfo.recreateAll
        projectInfo.modules.each { it.build = cicdInfo.buildAll || cicdInfo[it.name] }
    }

    stage('Clean environment if requested') {
        if (projectInfo.recreateAll) {
            deploymentUtils.removeAllMicroservices(projectInfo)
        }
    }

    def buildModules = [[],[],[]]
    projectInfo.modules.findAll { it.build }.eachWithIndex { module, i ->
        buildModules[i%3].add(module)
    }
    
    if (buildModules) {
        parallel(
            firstBucket: {
                stage("building first bucket of components to ${projectInfo.deployToNamespace}") {
                    buildModules[0].each { component ->
                        build(job: "../${projectInfo.id}/${component.name}-build-component", wait: true)
                    }
                }
            },
            secondBucket: {
                stage("building second bucket of components to ${projectInfo.deployToNamespace}") {
                    if (buildModules[1]) {
                        buildModules[1].each { component ->
                            build(job: "../${projectInfo.id}/${component.name}-build-artifact", wait: true)
                        }
                    }
                }
            },
            thirdBucket: {
                stage("building third bucket of components to ${projectInfo.deployToNamespace}") {
                    if (buildModules[2]) {
                        buildModules[2].each { component ->
                            build(job: "../${projectInfo.id}/${component.name}-build-to-dev", wait: true)
                        }
                    }
                }
            }
        )
    }
}
