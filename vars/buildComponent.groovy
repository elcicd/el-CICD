/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-to-dev-pipeline-template.
 *
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def component = projectInfo.components.find { it.name == args.componentName }

    component.scmBranch = args.scmBranch

    projectInfo.deployToEnv = projectInfo.devEnv
    projectInfo.deployToNamespace = args.deployToNamespace

    if (!projectInfo.builderNamespaces.find{ it == projectInfo.deployToNamespace})
    {
        loggingUtils.errorBanner("--> NAMESPACE NOT ALLOWED: ${projectInfo.deployToNamespace} <--",
                                  '',
                                  "BUILDS MAY ONLY DEPLOY TO ONE OF THE FOLLOWING NAMESPACES:",
                                  projectInfo.builderNamespaces.join(' '))
    }

    stage('Checkout code from repository') {
        loggingUtils.echoBanner("CLONING ${component.scmRepoName} REPO, REFERENCE: ${component.scmBranch}")

        projectUtils.cloneGitRepo(component, component.scmBranch)

        dir (component.workDir) {
            sh """
                ${shCmd.echo 'filesChanged:'}
                git diff HEAD^ HEAD --stat 2> /dev/null || :
            """
        }
    }

    def buildSteps = [el.cicd.BUILDER, el.cicd.TESTER, el.cicd.SCANNER, el.cicd.ASSEMBLER]
    buildSteps.each { buildStep ->
        stage("build step: run ${buildStep} for ${component.name}") {
            loggingUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR MICROSERVICE: ${component.name}")

            dir(component.workDir) {
                def moduleName = component[buildStep] ?: buildStep
                def builderModule = load "${el.cicd.BUILDER_STEPS_DIR}/${component.codeBase}/${moduleName}.groovy"

                switch(buildStep) {
                    case el.cicd.BUILDER:
                        builderModule.build(projectInfo, component)
                        break;
                    case el.cicd.TESTER:
                        builderModule.test(projectInfo, component)
                        break;
                    case el.cicd.SCANNER:
                        builderModule.scan(projectInfo, component)
                        break;
                    case el.cicd.ASSEMBLER:
                        builderModule.assemble(projectInfo, component)
                        break;
                }
            }
        }
    }

    stage('build, scan, and push image to repository') {
        projectInfo.imageTag = projectInfo.deployToNamespace - "${projectInfo.id}-"
        loggingUtils.echoBanner("BUILD ${component.id}:${projectInfo.imageTag} IMAGE")

        def imageRepo = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]

        def tlsVerify = el.cicd.DEV_IMAGE_REGISTRY_ENABLE_TLS ? "--tls-verify=${el.cicd.DEV_IMAGE_REGISTRY_ENABLE_TLS}" : ''

        withCredentials([string(credentialsId: el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REGISTRY_ACCESS_TOKEN_ID_POSTFIX}"],
                         variable: 'DEV_IMAGE_REGISTRY_ACCESS_TOKEN')]) {
            dir(component.workDir) {
                sh """
                    chmod 777 Dockerfile

                    echo "\nLABEL SRC_COMMIT_REPO='${component.repoUrl}'" >> Dockerfile
                    echo "\nLABEL SRC_COMMIT_BRANCH='${component.scmBranch}'" >> Dockerfile
                    echo "\nLABEL SRC_COMMIT_HASH='${component.srcCommitHash}'" >> Dockerfile
                    echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile

                    podman login ${tlsVerify} --username ${el.cicd.DEV_IMAGE_REGISTRY_USERNAME} --password \${DEV_IMAGE_REGISTRY_ACCESS_TOKEN} ${imageRepo}

                    podman build --build-arg=EL_CICD_BUILD_SECRETS_NAME=./${el.cicd.EL_CICD_BUILD_SECRETS_NAME} --squash \
                                 -t ${imageRepo}/${component.id}:${projectInfo.imageTag} -f ./Dockerfile
                """

                loggingUtils.echoBanner("SCAN ${component.id}:${projectInfo.imageTag} IMAGE")

                def imageScanner = load "${el.cicd.BUILDER_STEPS_DIR}/imageScanner.groovy"
                imageScanner.scanImage(projectInfo, component.name)

                loggingUtils.echoBanner("PUSH ${component.id}:${projectInfo.imageTag} IMAGE")

                sh """
                    podman push ${tlsVerify} ${imageRepo}/${component.id}:${projectInfo.imageTag}
                """
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        components: [component],
                        imageTag: projectInfo.imageTag,
                        recreate: args.recreate)
}
