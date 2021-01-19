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
    if (projectInfo.deployToNamespace != projectInfo.devNamespace &&
        !projectInfo.sandboxNamespaces.find{ it == projectInfo.deployToNamespace})
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
                git diff HEAD^ HEAD --stat 2> /dev/null || :
            """
        }
    }

    def buildSteps = [el.cicd.BUILDER, el.cicd.TESTER, el.cicd.SCANNER]
    buildSteps.each { buildStep ->
        stage("build step: run ${buildStep} for ${microService.name}") {
            pipelineUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR MICROSERVICE: ${microService.name}")

            dir(microService.workDir) {
                def moduleName = microService[buildStep] ?: buildStep
                def builderModule = load "${el.cicd.BUILDER_STEPS_DIR}/${microService.codeBase}/${moduleName}.groovy"

                switch(buildStep) {
                    case el.cicd.BUILDER:
                        builderModule.build(projectInfo.id, microService)
                        break;
                    case el.cicd.TESTER:
                        builderModule.test(projectInfo.id, microService)
                        break;
                    case el.cicd.SCANNER:
                        builderModule.scan(projectInfo.id, microService)
                        break;
                }
            }
        }
    }

    stage('build image and push to repository') {
        projectInfo.imageTag = projectInfo.devEnv
        if (projectInfo.deployToNamespace.contains(el.cicd.SANDBOX_NAMESPACE_BADGE)) {
            def index = projectInfo.deployToNamespace.split('-').last()
            projectInfo.imageTag = "${el.cicd.SANDBOX_NAMESPACE_BADGE}-${index}"
        }

        def imageRepo = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]
        def pullSecret = el.cicd["${projectInfo.DEV_ENV}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]
        def buildConfigName = "${microService.id}-${projectInfo.imageTag}"

        dir(microService.workDir) {
            sh """
                ${pipelineUtils.shellEchoBanner("BUILD ARTIFACT AND PUSH TO ARTIFACT REPOSITORY")}

                if [[ ! -n `oc get bc ${buildConfigName} -n ${projectInfo.cicdMasterNamespace} --ignore-not-found` ]]
                then
                    oc new-build --name ${buildConfigName} \
                                 --labels projectid=${projectInfo.id} \
                                 --binary=true \
                                 --strategy=docker \
                                 --to-docker \
                                 --to=${imageRepo}/${microService.id}:${projectInfo.imageTag} \
                                 --push-secret=${pullSecret} \
                                 -n ${projectInfo.cicdMasterNamespace}

                    oc set build-secret --pull bc/${buildConfigName} ${pullSecret} -n ${projectInfo.cicdMasterNamespace}
                fi

                chmod 777 Dockerfile
                echo "\nLABEL SRC_COMMIT_REPO='${microService.gitRepoUrl}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_BRANCH='${microService.gitBranch}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_HASH='${microService.srcCommitHash}'" >> Dockerfile
                echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile

                oc start-build ${buildConfigName} --from-dir=. --wait --follow -n ${projectInfo.cicdMasterNamespace}
            """
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: [microService],
                        imageTag: projectInfo.imageTag,
                        recreate: args.recreate)
}
