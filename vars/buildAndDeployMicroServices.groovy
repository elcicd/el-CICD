/*
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/build-and-deploy-microservices-pipeline-template.
 *
 */

def call(Map args) {

    def BUILDER = 0
    def TESTER = 1
    def SCANNER = 2

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo()

    def projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
    projectInfo.gitBranch = args.gitBranch
    projectInfo.deployToEnv = projectInfo.devEnv

    stage("Select sandbox, microservice, and branch") {
        String sandboxNamespaces = "${projectInfo.devNamespace}\n" + projectInfo.sandboxEnvs.collect { "${projectInfo.id}-sandbox-${it}" }.join('\n')

        List inputs = [choice(name: 'sandBoxNamespaces', description: 'Build Namespace', choices: sandboxNamespaces)]
        inputs += [booleanParam(name: 'freshEnvironment', description: 'Clean all from environment before deploying', default: false)]

        inputs += projectInfo.microServices.collect { microService ->
            string(name: "${microService.name}", description: "${microService.active ? '' : el.cicd.INACTIVE}")
        }

        def cicdInfo = input(message: "Select namepsace and microservices to build to:", parameters: inputs)

        projectInfo.deployToNamespace = cicdInfo.sandBoxNamespaces
        projectInfo.recreateAll = cicdInfo.freshEnvironment
        projectInfo.microServices.each { it.gitBranch = cicdInfo[it.name] }
    }

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
        def microServices = projectInfo.microServices.findAll { it.gitBranch }
        pipelineUtils.echoBanner("CLONING MICROSERVICE REPOS:", microServices.collect { it.gitBranch }.join(', '))

        projectInfo.microServices.findAll { it.gitBranch }.each { microService ->
            pipelineUtils.cloneGitRepo(microService, projectInfo.gitBranch)

            dir (microService.workDir) {
                sh """
                    ${shellEcho 'filesChanged:'}
                    git diff HEAD^ HEAD --stat || :
                """
            }
        }
    }

    stage('build step: build microservice') {
        pipelineUtils.echoBanner("BUILD APPLICATION")

        projectInfo.microServices.findAll { it.gitBranch }.each { microService ->
            dir(microService.workDir) {
                builderModules[BUILDER].build(projectInfo.id, microService.name)
            }
        }
    }

    stage('build step: run unit tests') {
        pipelineUtils.echoBanner("RUN UNIT TESTS")

        projectInfo.microServices.findAll { it.gitBranch }.each { microService ->
            dir(microService.workDir) {
                builderModules[TESTER].test(projectInfo.id, microService.name)
            }
        }
    }

    stage('build step: source code analysis') {
        pipelineUtils.echoBanner("SCAN CODE")

        projectInfo.microServices.findAll { it.gitBranch }.each { microService ->
            dir(microService.workDir) {
                builderModules[SCANNER].scan(projectInfo.id, microService.name)
            }
        }       
    }

    stage('build image and push to repository') {
        def imageRepo = el.cicd["${projectInfo.DEV_ENV}_IMAGE_REPO"]
        def pullSecret = el.cicd["${projectInfo.DEV_ENV}_IMAGE_REPO_PULL_SECRET"]
        
        projectInfo.microServices.findAll { it.gitBranch }.each { microService ->
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
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.gitBranch },
                        imageTag: projectInfo.deployToEnv,
                        recreate: args.recreate)
}
