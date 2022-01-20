/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-to-dev-pipeline-template.
 *
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def microService = projectInfo.microServices.find { it.name == args.microServiceName }

    microService.gitBranch = args.gitBranch

    projectInfo.deployToEnv = projectInfo.devEnv
    projectInfo.deployToNamespace = args.deployToNamespace

    if (!projectInfo.builderNamespaces.find{ it == projectInfo.deployToNamespace})
    {
        pipelineUtils.errorBanner("--> NAMESPACE NOT ALLOWED: ${projectInfo.deployToNamespace} <--",
                                  '',
                                  "BUILDS MAY ONLY DEPLOY TO ONE OF THE FOLLOWING NAMESPACES:",
                                  projectInfo.builderNamespaces.join(' '))
    }

    stage('Checkout code from repository') {
        pipelineUtils.echoBanner("CLONING ${microService.gitRepoName} REPO, REFERENCE: ${microService.gitBranch}")

        pipelineUtils.cloneGitRepo(microService, microService.gitBranch)

        dir (microService.workDir) {
            sh """
                ${shCmd.echo 'filesChanged:'}
                git diff HEAD^ HEAD --stat 2> /dev/null || :
            """
        }
    }

    def buildSteps = [el.cicd.BUILDER, el.cicd.TESTER, el.cicd.SCANNER, el.cicd.ASSEMBLER]
    buildSteps.each { buildStep ->
        stage("build step: run ${buildStep} for ${microService.name}") {
            pipelineUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR MICROSERVICE: ${microService.name}")

            dir(microService.workDir) {
                def moduleName = microService[buildStep] ?: buildStep
                def builderModule = load "${el.cicd.BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"

                switch(buildStep) {
                    case el.cicd.BUILDER:
                        builderModule.build(projectInfo, microService)
                        break;
                    case el.cicd.TESTER:
                        builderModule.test(projectInfo, microService)
                        break;
                    case el.cicd.SCANNER:
                        builderModule.scan(projectInfo, microService)
                        break;
                    case el.cicd.ASSEMBLER:
                        builderModule.assemble(projectInfo, microService)
                        break;
                }
            }
        }
    }

    stage('build image and push to repository') {
        pipelineUtils.echoBanner("BUILD IMAGE")

        projectInfo.imageTag = projectInfo.deployToNamespace - "${projectInfo.id}-"

        def imageRepo = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]
        def pullSecretName = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]
        def buildConfigName = "${microService.id}-${projectInfo.imageTag}"

        def tlsVerify = el.cicd.DEV_IMAGE_REPO_ENABLE_TLS ? "--tls-verify=${el.cicd.DEV_IMAGE_REPO_ENABLE_TLS}" : ''

        withCredentials([string(credentialsId: el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                         variable: 'DEV_IMAGE_REPO_ACCESS_TOKEN')]) {
            dir(microService.workDir) {
                sh """
                    chmod 777 Dockerfile

                    echo "\nLABEL SRC_COMMIT_REPO='${microService.gitRepoUrl}'" >> Dockerfile
                    echo "\nLABEL SRC_COMMIT_BRANCH='${microService.gitBranch}'" >> Dockerfile
                    echo "\nLABEL SRC_COMMIT_HASH='${microService.srcCommitHash}'" >> Dockerfile
                    echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile

                    podman login ${tlsVerify} --username ${el.cicd.DEV_IMAGE_REPO_USERNAME} --password \${DEV_IMAGE_REPO_ACCESS_TOKEN} ${imageRepo}

                    podman build --build-arg=EL_CICD_BUILD_SECRETS_NAME=./${el.cicd.EL_CICD_BUILD_SECRETS_NAME} --squash \
                                 -t ${imageRepo}/${microService.id}:${projectInfo.imageTag} -f ./Dockerfile
                """

                pipelineUtils.echoBanner("SCAN IMAGE")

                def imageScanner = load "${el.cicd.BUILDER_STEPS_DIR}/imageScanner.groovy"
                imageScanner.scanImage(projectInfo, microService.name)

                pipelineUtils.echoBanner("PUSH IMAGE")

                sh"""
                    podman push ${tlsVerify} ${imageRepo}/${microService.id}:${projectInfo.imageTag}
                """
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: [microService],
                        imageTag: projectInfo.imageTag,
                        recreate: args.recreate)
}
