/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the redeploy microservice pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/redeploy-removal-pipeline-template
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage('Checkout all microservice repositories') {
        pipelineUtils.echoBanner("CLONE ALL MICROSERVICE REPOSITORIES IN PROJECT")

        projectInfo.microServices.each { microService ->
            pipelineUtils.cloneGitRepo(microService, projectInfo.gitBranch)
        }
    }

    stage ('Select the environment to redeploy to or remove from') {
        def inputs = [choice(name: 'redeployEnv', description: '', choices: "${projectInfo.testEnvs.join('\n')}\n${projectInfo.preProdEnv}")]

        def cicdInfo = input(message: "Select environment to redeploy or remove microservices from/to:", parameters: inputs)

        projectInfo.deployToEnv = cicdInfo
        projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
        projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]
    }

    stage ('Select microservices and environment to redeploy to or remove from') {
        pipelineUtils.echoBanner("SELECT WHICH MICROSERVICES TO REDEPLOY OR REMOVE")

        def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.microservice}{":"}{.data.deployment-branch}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.deployToNamespace}"
        def msNameDepBranch = sh(returnStdout: true, script: script).split(' ')

        def inputs = []
        projectInfo.microServices.each { microService ->
            def branchPrefix = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.deployToEnv}-"
            def msToBranch = msNameDepBranch.find { it.startsWith("${microService.name}:${branchPrefix}") }

            microService.deploymentBranch = msToBranch ? msToBranch.split(':')[1] : ''
            microService.deploymentImageTag = microService.deploymentBranch.replaceAll("${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')

            dir(microService.workDir) {
                branchPrefix = "refs/remotes/**/${branchPrefix}*"
                def branchesAndTimesScript =
                    "git for-each-ref --count=5 --format='%(refname:short) (%(committerdate))' --sort='-committerdate' '${branchPrefix}'"
                def branchesAndTimes = sh(returnStdout: true, script: branchesAndTimesScript).trim()
                branchesAndTimes = branchesAndTimes.replaceAll("origin/${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')

                def deployLine
                branchesAndTimes.split('\n').each { line ->
                    deployLine = !deployLine && line.startsWith(microService.deploymentImageTag) ? line : deployLine
                }
                branchesAndTimes = deployLine ? branchesAndTimes.replace(deployLine, "${deployLine} <DEPLOYED>") : branchesAndTimes

                inputs += choice(name: microService.name,
                                 description: "status: ${microService.status}",
                                 choices: "${el.cicd.IGNORE}\n${branchesAndTimes}\n${el.cicd.REMOVE}")
            }
        }

        def cicdInfo = input(message: "Select microservices to redeploy in ${projectInfo.deployToEnv}",
                             parameters: inputs)

        def willRedeployOrRemove = false
        projectInfo.microServices.each { microService ->
            def answer = (inputs.size() > 1) ? cicdInfo[microService.name] : cicdInfo
            microService.remove = (answer == el.cicd.REMOVE)
            microService.redeploy = (answer && answer != el.cicd.REMOVE)

            if (microService.redeploy) {
                microService.deploymentImageTag = (answer =~ "${projectInfo.deployToEnv}-[0-9a-z]{7}")[0]
                microService.srcCommitHash = microService.deploymentImageTag.split('-')[1]
                microService.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${microService.deploymentImageTag}"
            }

            willRedeployOrRemove = willRedeployOrRemove || answer
        }

        if (!willRedeployOrRemove) {
            pipelineUtils.errorBanner("NO MICROSERVICES SELECTED FOR REDEPLOYMENT OR REMOVAL FOR ${projectInfo.deployToEnv}")
        }
    }

    stage('Verify image(s) exist for previous environment') {
        pipelineUtils.echoBanner("VERIFY IMAGE(S) TO REDEPLOY EXIST IN IMAGE REPOSITORY:",
                                 projectInfo.microServices.findAll{ it.redeploy }.collect { "${it.id}:${it.deploymentImageTag}" }.join(', '))

        def allImagesExist = true
        def errorMsgs = ["MISSING IMAGE(s) IN ${projectInfo.deployToNamespace} TO REDEPLOY:"]
        withCredentials([string(credentialsId: el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'TO_IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"] + ":\${TO_IMAGE_REPO_ACCESS_TOKEN}"
            projectInfo.microServices.each { microService ->
                if (microService.redeploy) {
                    def imageRepo = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_POSTFIX}"]
                    def imageUrl = "docker://${imageRepo}/${microService.id}:${microService.deploymentImageTag}"

                    def skopeoInspectCmd = "skopeo inspect --raw --tls-verify=${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX} --creds"
                    if (!sh(returnStdout: true, script: "${skopeoInspectCmd} ${imageRepoUserNamePwd} ${imageUrl} || :").trim()) {
                        errorMsgs << "    ${microService.id}:${projectInfo.deploymentImageTag} NOT FOUND IN ${projectInfo.deployToEnv} (${projectInfo.deployToNamespace})"
                    }
                }
            }
        }

        if (errorMsgs.size() > 1) {
            pipelineUtils.errorBanner(errorMsgs)
        }
    }

    stage('Checkout all deployment branches') {
        pipelineUtils.echoBanner("CHECKOUT ALL DEPLOYMENT BRANCHES")

        projectInfo.microServices.each { microService ->
            if (microService.redeploy) {
                dir(microService.workDir) {
                    sh "git checkout ${microService.deploymentBranch}"
                    microService.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
                }
            }
        }
    }

    stage('tag images to redeploy for environment') {
        pipelineUtils.echoBanner("TAG IMAGES FOR REPLOYMENT IN ENVIRONMENT TO ${projectInfo.deployToEnv}:",
                                 projectInfo.microServices.findAll{ it.redeploy }.collect { "${it.id}:${it.deploymentImageTag}" }.join(', '))

        withCredentials([string(credentialsId: el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'TO_IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"] + ":\${TO_IMAGE_REPO_ACCESS_TOKEN}"
            projectInfo.microServices.each { microService ->
                if (microService.redeploy) {
                    def imageRepo = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_POSTFIX}"]
                    def fromImageUrl = "${imageRepo}/${microService.id}:${microService.deploymentImageTag}"
                    def toImageUrl = "${imageRepo}/${microService.id}:${projectInfo.deployToEnv}"


                    def srcTlsVerify = "--src-tls-verify=${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"
                    def destTlsVerify = "--dest-tls-verify=${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"
                    def skopeoComd = "skopeo copy --src-creds ${imageRepoUserNamePwd} --dest-creds ${imageRepoUserNamePwd} ${srcTlsVerify} ${destTlsVerify}"
                    sh """
                        ${shellEcho '', "--> Tagging image '${microService.id}:${microService.deploymentImageTag}' as '${microService.id}:${projectInfo.deployToEnv}'"}

                        ${skopeoComd} docker://${fromImageUrl} docker://${toImageUrl}
                    """
                }
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.redeploy },
                        microServicesToRemove: projectInfo.microServices.findAll { it.remove },
                        imageTag: projectInfo.deployToEnv)
}
