/*
 * Defines the implementation of the microservice promotion pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/promotion-removal-pipeline-template
 *
 */

def call(Map args) {

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo()

    projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)

    stage ('Select microservices to promote and remove') {
        pipelineUtils.echoBanner("SELECT ENVIRONMENT TO PROMOTE TO AND MICROSERVICES TO PROMOTE OR REMOVE")

        def ENV_DELIMITER = ' to '
        def from
        def promotionChoices = []
        projectInfo.nonProdNamespaces.findAll { key, value ->
            if (key != projectInfo.prodEnv) {
                if (from) {
                    promotionChoices += "${from}${ENV_DELIMITER}${key}"
                }
                from = key
            }
        }
        def inputs = [choice(name: 'promotionEnvs', description: '', choices: "${promotionChoices.join('\n')}")]

        inputs += projectInfo.microServices.collect { microService ->
            choice(name: "${microService.name}",
                   description: "${microService.active ? '' : el.cicd.INACTIVE}",
                   choices: "${el.cicd.IGNORE}\n${el.cicd.PROMOTE}\n${el.cicd.REMOVE}")
        }

        def cicdInfo = input(message: "Select microservices to promote and environment to promote from/to:", parameters: inputs)

        def promoteOrRemove
        def promotionEnvs = cicdInfo.promotionEnvs.split(ENV_DELIMITER)
        projectInfo.deployFromEnv = promotionEnvs.first()
        projectInfo.ENV_FROM = projectInfo.deployFromEnv.toUpperCase()
        projectInfo.deployToEnv = promotionEnvs.last()
        projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
        projectInfo.deployFromNamespace = projectInfo.nonProdNamespaces[projectInfo.deployFromEnv]
        projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]

        projectInfo.microServices.each { microService ->
            def answer = (inputs.size() > 1) ? cicdInfo[microService.name] : cicdInfo
            microService.promote = answer == el.cicd.PROMOTE
            microService.remove = answer == el.cicd.REMOVE

            promoteOrRemove = promoteOrRemove || microService.promote != null
        }

        if (!promoteOrRemove) {
            pipelineUtils.errorBanner("NO MICROSERVICES SELECTED FOR PROMOTION OR REMOVAL FOR ${projectInfo.deployToEnv}")
        }
    }

    stage('Verify image(s) exist for previous environment') {
        pipelineUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE EXIST IN IMAGE REPOSITORY:", projectInfo.microServices.findAll{ it.promote }.collect { it.name }.join(', '))

        def allImagesExist = true
        def errorMsgs = ["MISSING IMAGE(s) IN ${projectInfo.deployFromNamespace} TO PROMOTE TO ${projectInfo.deployToNamespace}:"]
        withCredentials([string(credentialsId: el.cicd["${projectInfo.ENV_FROM}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'FROM_IMAGE_REPO_ACCESS_TOKEN')]) {
            def fromUserNamePwd = el.cicd["${projectInfo.ENV_FROM}_IMAGE_REPO_USERNAME"] + ":${FROM_IMAGE_REPO_ACCESS_TOKEN}"
            projectInfo.microServices.each { microService ->
                if (microService.promote) {
                    def imageRepo = el.cicd["${projectInfo.ENV_FROM}_IMAGE_REPO"]
                    def imageUrl = "docker://${imageRepo}/${microService.id}:${projectInfo.deployFromEnv}"
                    if (!sh(returnStdout: true, script: "skopeo inspect --raw --creds ${fromUserNamePwd} ${imageUrl} 2>&1 || :").trim()) {
                        errorMsgs += "    ${microService.id}:${projectInfo.deployFromEnv} NOT FOUND IN ${projectInfo.deployFromEnv} (${projectInfo.deployFromNamespace})"
                    }
                }
            }
        }

        if (errorMsgs.size() > 1) {
            pipelineUtils.errorBanner(errorMsgs)
        }
    }

    stage('Verify images are deployed in previous environment, collect source commit hash') {
        pipelineUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE DEPLOYED IN ${projectInfo.deployFromEnv}", projectInfo.microServices.findAll { it.promote }.collect { it.name }.join(', '))

        def script = """oc get cm -o jsonpath='{range .items[?(@.data.src-commit-hash)]}{.data.microservice}{":"}{.data.src-commit-hash}{" "}' -n ${projectInfo.deployFromNamespace}"""
        def commitHashMap =  sh(returnStdout: true, script: script).trim()
        commitHashMap = commitHashMap.split(' ').collectEntries { entry ->
            def pair = entry.split(':')
            [(pair.first()): pair.last()]
        }

        def microServicesMissingMsg = ["MISSING IMAGE(s) IN ${projectInfo.deployFromNamespace} TO PROMOTE TO ${projectInfo.deployToNamespace}"]
        projectInfo.microServices.each { microService ->
            if (microService.promote) {
                microService.srcCommitHash = commitHashMap[microService.name]
                if (!microService.srcCommitHash) {
                    microServicesMissingMsg += "    ${microService.id} NOT FOUND IN ${projectInfo.deployFromNamespace}"
                }
            }
        }

        if (microServicesMissingMsg.size() > 1) {
            pipelineUtils.errorBanner(microServicesMissingMsg)
        }
    }

    stage('Checkout all microservice repositories') {
        pipelineUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES:", projectInfo.microServices.findAll{ it.promote }.collect { it. name }.join(', '))

        projectInfo.microServices.each { microService ->
            if (microService.promote) {
                dir(microService.workDir) {
                    pipelineUtils.cloneGitRepo(microService, microService.srcCommitHash)

                    pipelineUtils.assignDeploymentBranchName(projectInfo, microService, projectInfo.deployToEnv)
                    microService.deployBranchExists = sh(returnStdout: true, script: "git show-ref refs/remotes/origin/${microService.deploymentBranch} || : | tr -d '[:space:]'")
                    microService.deployBranchExists = !microService.deployBranchExists.isEmpty()

                    def ref = microService.deployBranchExists ? microService.deploymentBranch : microService.previousDeploymentBranchName
                    if (ref) {
                        sh "git checkout ${ref}"
                    }

                    microService.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
                }
            }
        }
    }

    stage('promote images') {
        pipelineUtils.echoBanner("PROMOTE IMAGES FROM ${projectInfo.deployFromNamespace} ENVIRONMENT TO ${projectInfo.deployToNamespace} ENVIRONMENT FOR:",
                                 projectInfo.microServices.findAll{ it.promote }.collect { it. name }.join(', '))

        withCredentials([string(credentialsId: el.cicd["${projectInfo.ENV_FROM}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'FROM_IMAGE_REPO_ACCESS_TOKEN'),
                         string(credentialsId: el.cicd["${projectInfo.ENV_TO}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'TO_IMAGE_REPO_ACCESS_TOKEN')])
        {
            def fromUserNamePwd = el.cicd["${projectInfo.ENV_FROM}_IMAGE_REPO_USERNAME"] + ":${FROM_IMAGE_REPO_ACCESS_TOKEN}"
            def toUserNamePwd = el.cicd["${projectInfo.ENV_TO}_IMAGE_REPO_USERNAME"] + ":${TO_IMAGE_REPO_ACCESS_TOKEN}"
            projectInfo.microServices.each { microService ->
                if (microService.promote) {
                    def fromImageRepo = el.cicd["${projectInfo.ENV_FROM}_IMAGE_REPO"]
                    def fromImageUrl = "${fromImageRepo}/${microService.id}"

                    def toImageRepo = el.cicd["${projectInfo.ENV_TO}_IMAGE_REPO"]
                    def promoteTag = "${projectInfo.deployToEnv}-${microService.srcCommitHash}"
                    def deployToImgUrl = "${toImageRepo}/${microService.id}"

                    def skopeoPromoteCmd = "skopeo copy --src-creds ${fromUserNamePwd} --dest-creds ${toUserNamePwd} --src-tls-verify=false --dest-tls-verify=false"

                    def skopeoTagCmd = "skopeo copy --src-creds ${toUserNamePwd} --dest-creds ${toUserNamePwd} --src-tls-verify=false --dest-tls-verify=false"

                    sh """
                        ${skopeoPromoteCmd} docker://${fromImageUrl}:${projectInfo.deployFromEnv} docker://${deployToImgUrl}:${promoteTag}

                        ${skopeoTagCmd} docker://${deployToImgUrl}:${promoteTag} docker://${deployToImgUrl}:${projectInfo.deployToEnv}

                        ${shellEcho  "--> ${fromImageUrl} promoted to ${promotedImageUrl} and ${deployToImgUrl}"}
                    """
                }
            }
        }
    }

    stage('Create deployment branch if necessary') {
        pipelineUtils.echoBanner("CREATE DEPLOYMENT BRANCH(ES) FOR PROMOTION RELEASE:", projectInfo.microServices.findAll{ it.promote }.collect { it. name }.join(', '))

        projectInfo.microServices.each { microService ->
            if (microService.promote && !microService.deployBranchExists) {
                dir(microService.workDir) {
                    withCredentials([sshUserPrivateKey(credentialsId: microService.gitSshPrivateKeyName, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                        sh """
                            ${shellEcho  "-> Creating configuration branch: ${microService.deploymentBranch}"}
                            ${sshAgentBash GITHUB_PRIVATE_KEY,
                                           "git branch ${microService.deploymentBranch}",
                                           "git push origin ${microService.deploymentBranch}:${microService.deploymentBranch}"}
                        """
                    }
                }
            }
            else if (microService.promote) {
                echo "-> Configuration branch already exists: ${microService.deploymentBranch}"
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.promote },
                        microServicesToRemove: projectInfo.microServices.findAll { it.remove },
                        imageTag: projectInfo.deployToEnv)
}
