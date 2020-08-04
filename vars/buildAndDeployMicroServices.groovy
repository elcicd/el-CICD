/*
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/build-and-deploy-microservices-pipeline-template.
 *
 */

def call(Map args) {

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo()

    def projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
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

    stage('Checkout code from repository') {
        pipelineUtils.echoBanner("CLONING MICROSERVICE REPOS:", projectInfo.microServices.findAll { it.gitBranch }.join(', '))

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
    
    builderUtils.buildTestAndScan(projectInfo)

    stage('build image and push to repository') {
        def imageRepo = el.cicd["${projectInfo.DEV_ENV}_IMAGE_REPO"]
        def pullSecret = el.cicd["${projectInfo.DEV_ENV}_IMAGE_REPO_PULL_SECRET"]
        
        def imageTag = projectInfo.devEnv
        def buildConfigName = microService.id

        if (projectInfo.deployToNamespace.contains(el.cicd.SANDBOX_NAMESPACE_BADGE)) {
            def index = projectInfo.deployToNamespace.split('-').last()
            def postfix = "${el.cicd.SANDBOX_NAMESPACE_BADGE}-${index}"
            buildConfigName += "-${postfix}"
            imageTag = postfix.toUpperCase()
        }
        
        projectInfo.microServices.findAll { it.gitBranch }.each { microService ->
            dir(microService.workDir) {
                sh """
                    ${pipelineUtils.shellEchoBanner("BUILD ARTIFACT AND PUSH TO ARTIFACT REPOSITORY")}
    
                    if [[ ! -n `oc get bc ${buildConfigName} -n ${projectInfo.nonProdCicdNamespace} --ignore-not-found` ]]
                    then
                        oc new-build --name ${buildConfigName} \
                                     --binary=true \
                                     --strategy=docker \
                                     --to-docker \
                                     --to=${imageRepo}/${microService.id}:${imageTag} \
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
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.gitBranch },
                        imageTag: projectInfo.deployToEnv,
                        recreate: args.recreate)
}
