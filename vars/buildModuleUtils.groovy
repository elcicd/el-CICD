/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */
 
 def cloneModule(def module) {
    loggingUtils.echoBanner("CLONING ${module.gitRepoName} REPO, REFERENCE: ${module.gitBranch}")

    projectInfoUtils.cloneGitRepo(module, module.gitBranch) {
        sh """
            ${shCmd.echo 'filesChanged:'}
            git diff HEAD^ HEAD --stat 2> /dev/null || :
        """
    
        module.srcCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
    }
}

def runBuildStep(def projectInfo, def module, def buildStep, def moduleType) {
    def buildStepName = module[buildStep] ?: buildStep
    loggingUtils.echoBanner("RUN ${buildStepName.toUpperCase()} FOR ${moduleType}: ${module.name}")

    dir(module.workDir) {
        def pipelineBuildStepFile = "${el.cicd.BUILDER_STEPS_DIR}/${module.codeBase}/${buildStepName}.groovy"
        pipelineBuildStepFile = fileExists(pipelineBuildStepFile) ? 
            pipelineBuildStepFile :  "${el.cicd.BUILDER_STEPS_DIR}/${buildStepName}.groovy"

        def builderModule = load(pipelineBuildStepFile)
        switch(buildStep) {
            case el.cicd.BUILDER:
                builderModule.build(projectInfo, module)
                break;
            case el.cicd.TESTER:
                builderModule.test(projectInfo, module)
                break;
            case el.cicd.ANALYZER:
                builderModule.scan(projectInfo, module)
                break;
            case el.cicd.ASSEMBLER:
                builderModule.package(projectInfo, module)
                break;
        }
    }
}

def buildScanAndPushImage(def projectInfo, def module)
    module.imageTag = projectInfo.deployToNamespace - "${projectInfo.id}-"
    loggingUtils.echoBanner("BUILDING ${module.id}:${module.imageTag} IMAGE")

    def imageRepo = el.cicd["${projectInfo.DEV_ENV}${el.cicd.OCI_REGISTRY_POSTFIX}"]

    def tlsVerify = el.cicd.DEV_OCI_REGISTRY_ENABLE_TLS ? "--tls-verify=${el.cicd.DEV_OCI_REGISTRY_ENABLE_TLS}" : ''

    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.devEnv),
                    usernameVariable: 'DEV_OCI_REGISTRY_USERNAME',
                    passwordVariable: 'DEV_OCI_REGISTRY_PWD')]) {
        dir(module.workDir) {
            sh """
                chmod 777 Dockerfile
            
                echo "\nLABEL SRC_COMMIT_REPO='${module.gitRepoUrl}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_BRANCH='${module.gitBranch}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_HASH='${module.srcCommitHash}'" >> Dockerfile
                echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile
                
                podman login ${tlsVerify} --username \${DEV_OCI_REGISTRY_USERNAME} --password \${DEV_OCI_REGISTRY_PWD} ${imageRepo}

                podman build --build-arg=EL_CICD_BUILD_SECRETS_NAME=./${el.cicd.EL_CICD_BUILD_SECRETS_NAME} --squash \
                            -t ${imageRepo}/${module.id}:${module.imageTag} -f ./Dockerfile
            """

            loggingUtils.echoBanner("SCAN ${module.id}:${module.imageTag} IMAGE")

            def pipelineBuildStepFile = "${el.cicd.BUILDER_STEPS_DIR}/${module.codeBase}/${el.cicd.SCANNER}.groovy"
            pipelineBuildStepFile = fileExists(pipelineBuildStepFile) ? 
                pipelineBuildStepFile :  "${el.cicd.BUILDER_STEPS_DIR}/${el.cicd.SCANNER}.groovy"
                
            def imageScanner = load(imageScannerFile)
            imageScanner.scanImage(projectInfo, module.name)

            loggingUtils.echoBanner("PUSH ${module.id}:${module.imageTag} IMAGE")

            sh """
                podman push ${tlsVerify} ${imageRepo}/${module.id}:${module.imageTag}
            """
        }
    }
}