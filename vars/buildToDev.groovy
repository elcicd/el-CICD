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
    projectInfo.deployToNamespace = projectInfo.devNamespace


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
    
    builderUtils.buildTestAndScan(projectInfo)

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
