/*
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/build-to-dev-pipeline-template.
 *
 */

void call(Map args) {

    def BUILDER = 0
    def TESTER = 1
    def SCANNER = 2

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo() 

    def projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
    projectInfo.gitBranch = args.gitBranch
    projectInfo.deployToEnv = projectInfo.devEnv
    projectInfo.deployToNamespace = projectInfo.devNamespace

    def microService = projectInfo.microServices.find { it.name == args.microServiceName }

    def builderModules = [:]
    dir ("${el.cicd.EL_CICD_DIR}/builder-steps/${args.codeBase}") {
        def module = microService.builder ?: 'builder'
        builderModules[BUILDER] = load "${module}.groovy"

        module = microService.tester ?: 'tester'
        builderModules[TESTER] = load "${module}.groovy"

        module = microService.scanner ?: 'scanner'
        builderModules[SCANNER] = load "${module}.groovy"
    }

    stage('Checkout code from repository') {
        pipelineUtils.echoBanner("CLONING ${microService.gitRepoName} REPO, REFERENCE: ${projectInfo.gitBranch}")

        pipelineUtils.cloneGitRepo(microService, projectInfo.gitBranch)

        dir (microService.workDir) {
            sh """
                ${shellEcho 'filesChanged:'}
                git diff HEAD^ HEAD --stat || :
            """
        }
    }

    stage('build step: build microservice') {
        pipelineUtils.echoBanner("BUILD APPLICATION")

        dir(microService.workDir) {
            builderModules[BUILDER].build(projectInfo.id, microService.name)
        }
    }

    stage('build step: run unit tests') {
        pipelineUtils.echoBanner("RUN UNIT TESTS")

        dir(microService.workDir) {
            builderModules[TESTER].test(projectInfo.id, microService.name)
        }
    }

    stage('build step: source code analysis') {
        pipelineUtils.echoBanner("SCAN CODE")

        dir(microService.workDir) {
            builderModules[SCANNER].scan(projectInfo.id, microService.name)
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
                echo "\nLABEL SRC_COMMIT_BRANCH='${projectInfo.gitBranch}'" >> Dockerfile
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
