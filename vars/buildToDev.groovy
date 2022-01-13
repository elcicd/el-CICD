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
        pipelineUtils.echoBanner("BUILD IMAGE AND PUSH TO REPOSITORY")

        projectInfo.imageTag = projectInfo.deployToNamespace - "${projectInfo.id}-"

        def imageRepo = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]
        def pullSecretName = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]
        def buildConfigName = "${microService.id}-${projectInfo.imageTag}"

        dir(microService.workDir) {
            sh """
                # HAS_BC=\$(oc get bc --no-headers -o custom-columns=:.metadata.name --ignore-not-found ${buildConfigName} -n ${projectInfo.cicdMasterNamespace})
                # if [[ -z \${HAS_BC} ]]
                # then
                #     oc new-build --name ${buildConfigName} \
                #                  --labels projectid=${projectInfo.id} \
                #                  --binary=true \
                #                  --strategy=docker \
                #                  --to-docker \
                #                  --to=${imageRepo}/${microService.id}:${projectInfo.imageTag} \
                #                  --push-secret=${pullSecretName} \
                #                  --build-secret=${el.cicd.EL_CICD_BUILD_SECRETS_NAME}:${el.cicd.EL_CICD_BUILD_SECRETS_NAME} \
                #                  -n ${projectInfo.cicdMasterNamespace}

                #     oc set build-secret --pull bc/${buildConfigName} ${pullSecretName} -n ${projectInfo.cicdMasterNamespace}
                # fi

                chmod 777 Dockerfile
                # sed -i '/^FROM.*/a ARG EL_CICD_BUILD_SECRETS_NAME=./${el.cicd.EL_CICD_BUILD_SECRETS_NAME}' Dockerfile

                echo "\$(id -un):1000000:65536" > /etc/subuid
                echo "0:1100000:65536" >> /etc/subuid
                echo "\$(id -un):1000000:65536" > /etc/subgid
                echo "0:1100000:65536" >> /etc/subgid
                echo
                echo '================'
                echo "cat /etc/subuid:\n\$(cat /etc/subuid)"
                echo "cat /etc/subgid:\n\$(cat /etc/subgid)"
                echo
                id
                echo '================'
                echo

                echo "\nLABEL SRC_COMMIT_REPO='${microService.gitRepoUrl}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_BRANCH='${microService.gitBranch}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_HASH='${microService.srcCommitHash}'" >> Dockerfile
                echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile

                buildah unshare buildah bud --log-level='debug' \
                    --build-arg=EL_CICD_BUILD_SECRETS_NAME=./${el.cicd.EL_CICD_BUILD_SECRETS_NAME} \
                    -t ${imageRepo}/${microService.id}:${projectInfo.imageTag}

                # oc start-build ${buildConfigName} --from-dir=. --wait --follow -n ${projectInfo.cicdMasterNamespace}
            """
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: [microService],
                        imageTag: projectInfo.imageTag,
                        recreate: args.recreate)
}
