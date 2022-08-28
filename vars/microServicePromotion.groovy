/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the microservice promotion pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/promotion-removal-pipeline-template
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select microservices to promote and remove') {
        loggingUtils.echoBanner("SELECT ENVIRONMENT TO PROMOTE TO AND MICROSERVICES TO DEPLOY OR REMOVE")

        def ENV_DELIMITER = ' to '
        def fromEnv = projectInfo.devEnv
        def promotionChoices = []
        projectInfo.testEnvs.findAll { toEnv ->
            promotionChoices << "${fromEnv}${ENV_DELIMITER}${toEnv}"
            fromEnv = toEnv
        }
        promotionChoices << "${fromEnv}${ENV_DELIMITER}${projectInfo.preProdEnv}"
        projectInfo.allowsHotfixes && (promotionChoices << "${el.cicd.hotfixEnv}${ENV_DELIMITER}${el.cicd.preProdEnv}")

        def inputs = [choice(name: 'promotionEnvs', description: '', choices: "${promotionChoices.join('\n')}"),
                      choice(name: 'defaultAction',
                             description: 'Default action to apply to all microservices',
                             choices: "${el.cicd.IGNORE}\n${el.cicd.PROMOTE}\n${el.cicd.REMOVE}")]

        inputs += projectInfo.microServices.collect { microService ->
            choice(name: microService.name,
                   description: "status: ${microService.status}",
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
            microService.promote = answer == el.cicd.PROMOTE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.PROMOTE)
            microService.remove = answer == el.cicd.REMOVE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.REMOVE)

            promoteOrRemove = promoteOrRemove || microService.promote != null
        }

        if (!promoteOrRemove) {
            loggingUtils.errorBanner("NO MICROSERVICES SELECTED FOR PROMOTION OR REMOVAL FOR ${projectInfo.deployToEnv}")
        }

        projectInfo.microServicesToPromote = projectInfo.microServices.findAll{ it.promote }
        projectInfo.microServicesToRemove = projectInfo.microServices.findAll{ it.remove }
    }

    if (projectInfo.microServicesToPromote) {
        stage('Verify image(s) exist for previous environment') {
            loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE EXIST IN IMAGE REPOSITORY:", projectInfo.microServicesToPromote.collect { it.name }.join(', '))

            def errorMsgs = ["MISSING IMAGE(s) IN ${projectInfo.deployFromNamespace} TO PROMOTE TO ${projectInfo.deployToNamespace}:"]

            withCredentials([string(credentialsId: el.cicd["${projectInfo.ENV_FROM}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                             variable: 'FROM_IMAGE_REGISTRY_ACCESS_TOKEN')]) {
                projectInfo.microServicesToPromote.each { microService ->
                    def verifyImageCmd =
                        shCmd.verifyImage(projectInfo.ENV_FROM, 'FROM_IMAGE_REGISTRY_ACCESS_TOKEN', microService.id, projectInfo.deployFromEnv)
                    if (!sh(returnStdout: true, script: "${verifyImageCmd}").trim()) {
                        def image = "${microService.id}:${projectInfo.deployFromEnv}"
                        errorMsgs << "    ${image} NOT FOUND IN ${projectInfo.deployFromEnv} (${projectInfo.deployFromNamespace})"
                    }
                    else {
                        def imageRepo = el.cicd["${projectInfo.ENV_FROM}${el.cicd.IMAGE_REPO_POSTFIX}"]
                        echo "VERIFIED: ${microService.id}:${projectInfo.deployFromEnv} IN ${imageRepo}"
                    }
                }
            }

            if (errorMsgs.size() > 1) {
                loggingUtils.errorBanner(errorMsgs)
            }
        }

        stage('Verify images are deployed in previous environment, collect source commit hash') {
            loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE ARE DEPLOYED IN ${projectInfo.deployFromEnv}",
                                     projectInfo.microServicesToPromote.collect { it.name }.join(', '))

            def jsonPathSingle = '''jsonpath='{.data.microservice}{":"}{.data.src-commit-hash}{" "}' '''
            def jsonPathMulti = '''jsonpath='{range .items[*]}{.data.microservice}{":"}{.data.src-commit-hash}{" "}{end}' '''

            def msNames = projectInfo.microServicesToPromote.collect { "${it.id}-${el.cicd.CM_META_INFO_POSTFIX}" }
            def jsonPath =  (msNames.size() > 1) ? jsonPathMulti : jsonPathSingle
            def script = "oc get cm --ignore-not-found ${msNames.join(' ')} -o ${jsonPath} -n ${projectInfo.deployFromNamespace}"

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
                        microServicesMissingMsg += "${microService.id} NOT FOUND IN ${projectInfo.deployFromNamespace}"
                    }
                }
            }

            if (microServicesMissingMsg.size() > 1) {
                loggingUtils.errorBanner(microServicesMissingMsg)
            }
        }

        stage('Checkout all microservice repositories') {
            loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES:", projectInfo.microServicesToPromote.collect { it. name }.join(', '))

            projectInfo.microServices.each { microService ->
                if (microService.promote) {
                    dir(microService.workDir) {
                        projectUtils.cloneGitRepo(microService, microService.srcCommitHash)

                        microService.previousDeploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, microService, projectInfo.deployFromEnv)
                        microService.deploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, microService, projectInfo.deployToEnv)

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

        stage("promote images") {
            loggingUtils.echoBanner("PROMOTE IMAGES FROM ${projectInfo.deployFromNamespace} ENVIRONMENT TO ${projectInfo.deployToNamespace} ENVIRONMENT FOR:",
                                    projectInfo.microServicesToPromote.collect { it. name }.join(', '))

            withCredentials([string(credentialsId: el.cicd["${projectInfo.ENV_FROM}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'FROM_IMAGE_REGISTRY_ACCESS_TOKEN'),
                            string(credentialsId: el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'TO_IMAGE_REGISTRY_ACCESS_TOKEN')])
            {
                projectInfo.microServicesToPromote.each { microService ->
                    def promoteTag = "${projectInfo.deployToEnv}-${microService.srcCommitHash}"
                    def copyImage =
                        shCmd.copyImage(projectInfo.ENV_FROM,
                                                'FROM_IMAGE_REGISTRY_ACCESS_TOKEN',
                                                microService.id,
                                                projectInfo.deployFromEnv,
                                                projectInfo.ENV_TO,
                                                'TO_IMAGE_REGISTRY_ACCESS_TOKEN',
                                                microService.id,
                                                promoteTag)

                    def tagImage =
                        shCmd.tagImage(projectInfo.ENV_TO,
                                               'TO_IMAGE_REGISTRY_ACCESS_TOKEN',
                                               microService.id,
                                               promoteTag,
                                               projectInfo.deployToEnv)

                    def msg = "${microService.id} image promoted and tagged as ${promoteTag} and ${projectInfo.deployToEnv}"
                    sh """
                        ${shCmd.echo ''}
                        ${copyImage}

                        ${shCmd.echo ''}
                        ${tagImage}

                        ${shCmd.echo ''}
                        ${shCmd.echo  "--> ${msg}"}
                    """
                }
            }
        }

        stage('Create deployment branch if necessary') {
            loggingUtils.echoBanner("CREATE DEPLOYMENT BRANCH(ES) FOR PROMOTION RELEASE:",
                                     projectInfo.microServicesToPromote.collect { it. name }.join(', '))

            projectInfo.microServices.each { microService ->
                if (microService.promote && !microService.deployBranchExists) {
                    dir(microService.workDir) {
                        withCredentials([sshUserPrivateKey(credentialsId: microService.gitDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                            sh """
                                ${shCmd.echo  "-> Creating Deployment Branch for the image ${microService.id}: ${microService.deploymentBranch}"}
                                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY',
                                                     "git branch ${microService.deploymentBranch}",
                                                     "git push origin ${microService.deploymentBranch}:${microService.deploymentBranch}")}
                            """
                        }
                    }
                }
                else if (microService.promote) {
                    echo "-> Deployment Branch already exists for the image ${microService.id}: ${microService.deploymentBranch}"
                }
            }
        }
    }

    if (projectInfo.microServicesToPromote) {
        deployMicroServices(projectInfo: projectInfo,
                            microServices: projectInfo.microServicesToPromote,
                            microServicesToRemove: projectInfo.microServicesToRemove,
                            imageTag: projectInfo.deployToEnv)
    }
    else {
        deployMicroServices(projectInfo: projectInfo, microServicesToRemove: projectInfo.microServicesToRemove)
    }
}
