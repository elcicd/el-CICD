/*
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/build-to-dev-pipeline-template.
 *
 */

void call(Map args) {

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo() 

    def projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
    def microService = projectInfo.microServices.find { it.name == args.microServiceName }
    
    microService.gitBranch = args.gitBranch
    
    projectInfo.deployToEnv = projectInfo.devEnv
    projectInfo.deployToNamespace = args.deployToNamespace
    if (projectInfo.deployToNamespace != projectInfo.devNamespace &&
        !projectInfo.sandboxNamespaces.contains(projectInfo.deployToNamespace))
    {
        def sboxNamepaces = projectInfo.sandboxNamespaces.join(' ')
        pipelineUtils.errorBanner("--> NAMESPACE NOT ALLOWED: ${projectInfo.deployToNamespace} <--", '',
                                  "BUILDS MAY ONLY DEPLOY TO ONE OF THE FOLLOWING NAMESPACES:",
                                  "${projectInfo.devNamespace} ${sboxNamepaces}")
    }

    stage('Checkout code from repository') {
        pipelineUtils.echoBanner("CLONING ${microService.gitRepoName} REPO, REFERENCE: ${microService.gitBranch}")

        pipelineUtils.cloneGitRepo(microService, microService.gitBranch)

        dir (microService.workDir) {
            sh """
                ${shellEcho 'filesChanged:'}
                git diff HEAD^ HEAD --stat || :
            """
        }
    }
    
    stage("build step: build ${microService.name}") {
        pipelineUtils.echoBanner("BUILD MICROSERVICE: ${microService.name}")

        dir(microService.workDir) {
            def moduleName = microService[el.cicd.BUILDER] ?: el.cicd.BUILDER
            def builderModule = load "${el.cicd.EL_CICD_BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
            builderModule.build(projectInfo.id, microService.name)
        }
    }

    stage("build step: run unit tests for ${microService.name}") {
        pipelineUtils.echoBanner("RUN UNIT TESTS: ${microService.name}")

        dir(microService.workDir) {
            def moduleName = microService[el.cicd.TESTER] ?: el.cicd.TESTER
            def testerModule = load "${el.cicd.EL_CICD_BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
            testerModule.test(projectInfo.id, microService.name)
        }
    }

    stage("build step: source code analysis for ${microService.name}") {
        pipelineUtils.echoBanner("SCAN CODE: ${microService.name}")

        dir(microService.workDir) {
            def moduleName = microService[el.cicd.SCANNER] ?: el.cicd.SCANNER
            def scannerModule = load "${el.cicd.EL_CICD_BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"
            scannerModule.scan(projectInfo.id, microService.name)
        }
    }

    stage('build image and push to repository') {
        def imageRepo = el.cicd["${projectInfo.DEV_ENV}_IMAGE_REPO"]
        def pullSecret = el.cicd["${projectInfo.DEV_ENV}_IMAGE_REPO_PULL_SECRET"]
        dir(microService.workDir) {
            sh """
                ${pipelineUtils.shellEchoBanner("BUILD ARTIFACT AND PUSH TO ARTIFACT REPOSITORY")}

                if [[ ! -n `oc get bc ${microService.id} -n ${projectInfo.nonProdCicdNamespace} --ignore-not-found` ]]
                then
                    oc new-build --name ${microService.id} \
                                 --binary=true \
                                 --strategy=docker \
                                 --to-docker \
                                 --to=${imageRepo}/${microService.id}:${projectInfo.deployToEnv} \
                                 --push-secret=${pullSecret} \
                                 -n ${projectInfo.nonProdCicdNamespace}

                    oc set build-secret --pull bc/${microService.id} ${pullSecret} -n ${projectInfo.nonProdCicdNamespace}
                fi

                chmod 777 Dockerfile
                echo "\nLABEL SRC_COMMIT_REPO='${microService.gitRepoUrl}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_BRANCH='${microService.gitBranch}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_HASH='${microService.srcCommitHash}'" >> Dockerfile
                echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile

                oc start-build ${microService.id} --from-dir=. --wait --follow -n ${projectInfo.nonProdCicdNamespace}
            """
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: [microService],
                        imageTag: projectInfo.deployToEnv,
                        recreate: args.recreate)
}
