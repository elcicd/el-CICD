/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the component promotion pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/promotion-removal-pipeline-template
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage ('Select components to promote and remove') {
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
                             description: 'Default action to apply to all components',
                             choices: "${el.cicd.IGNORE}\n${el.cicd.PROMOTE}\n${el.cicd.REMOVE}")]

        inputs += projectInfo.components.collect { component ->
            choice(name: component.name,
                   description: "status: ${component.status}",
                   choices: "${el.cicd.IGNORE}\n${el.cicd.PROMOTE}\n${el.cicd.REMOVE}")
        }

        def cicdInfo = input(message: "Select components to promote and environment to promote from/to:", parameters: inputs)

        def promoteOrRemove
        def promotionEnvs = cicdInfo.promotionEnvs.split(ENV_DELIMITER)
        projectInfo.deployFromEnv = promotionEnvs.first()
        projectInfo.ENV_FROM = projectInfo.deployFromEnv.toUpperCase()
        projectInfo.deployToEnv = promotionEnvs.last()
        projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
        projectInfo.deployFromNamespace = projectInfo.nonProdNamespaces[projectInfo.deployFromEnv]
        projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]

        projectInfo.components.each { component ->
            def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
            component.promote = answer == el.cicd.PROMOTE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.PROMOTE)
            component.remove = answer == el.cicd.REMOVE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.REMOVE)

            promoteOrRemove = promoteOrRemove || component.promote != null
        }

        if (!promoteOrRemove) {
            loggingUtils.errorBanner("NO MICROSERVICES SELECTED FOR PROMOTION OR REMOVAL FOR ${projectInfo.deployToEnv}")
        }

        projectInfo.componentsToPromote = projectInfo.components.findAll{ it.promote }
        projectInfo.componentsToRemove = projectInfo.components.findAll{ it.remove }
    }

    if (projectInfo.componentsToPromote) {
        stage('Verify image(s) exist for previous environment') {
            loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE EXIST IN IMAGE REPOSITORY:", projectInfo.componentsToPromote.collect { it.name }.join(', '))

            def errorMsgs = ["MISSING IMAGE(s) IN ${projectInfo.deployFromNamespace} TO PROMOTE TO ${projectInfo.deployToNamespace}:"]

            withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployFromEnv),
                                              usernameVariable: 'FROM_IMAGE_REGISTRY_USERNAME',
                                              passwordVariable: 'FROM_IMAGE_REGISTRY_PWD')])
            {
                projectInfo.componentsToPromote.each { component ->
                    def verifyImageCmd = shCmd.verifyImage(projectInfo.ENV_FROM,
                                                           'FROM_IMAGE_REGISTRY_USERNAME',
                                                           'FROM_IMAGE_REGISTRY_PWD',
                                                            component.id,
                                                            projectInfo.deployFromEnv)

                    if (!sh(returnStdout: true, script: "${verifyImageCmd}").trim()) {
                        def image = "${component.id}:${projectInfo.deployFromEnv}"
                        errorMsgs << "    ${image} NOT FOUND IN ${projectInfo.deployFromEnv} (${projectInfo.deployFromNamespace})"
                    }
                    else {
                        def imageRepo = el.cicd["${projectInfo.ENV_FROM}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
                        echo "VERIFIED: ${component.id}:${projectInfo.deployFromEnv} IN ${imageRepo}"
                    }
                }
            }

            if (errorMsgs.size() > 1) {
                loggingUtils.errorBanner(errorMsgs)
            }
        }

        stage('Verify images are deployed in previous environment, collect source commit hash') {
            loggingUtils.echoBanner("VERIFY IMAGE(S) TO PROMOTE ARE DEPLOYED IN ${projectInfo.deployFromEnv}",
                                     projectInfo.componentsToPromote.collect { it.name }.join(', '))

            def jsonPathSingle = '''jsonpath='{.data.component}{":"}{.data.src-commit-hash}{" "}' '''
            def jsonPathMulti = '''jsonpath='{range .items[*]}{.data.component}{":"}{.data.src-commit-hash}{" "}{end}' '''

            def compNames = projectInfo.componentsToPromote.collect { "${it.id}-${el.cicd.META_INFO_POSTFIX}" }
            def jsonPath =  (compNames.size() > 1) ? jsonPathMulti : jsonPathSingle
            def script = "oc get cm --ignore-not-found ${compNames.join(' ')} -o ${jsonPath} -n ${projectInfo.deployFromNamespace}"

            def commitHashMap =  sh(returnStdout: true, script: script).trim()
            commitHashMap = commitHashMap.split(' ').collectEntries { entry ->
                def pair = entry.split(':')
                [(pair.first()): pair.last()]
            }

            def componentsMissingMsg = ["MISSING IMAGE(s) IN ${projectInfo.deployFromNamespace} TO PROMOTE TO ${projectInfo.deployToNamespace}"]
            projectInfo.components.each { component ->
                if (component.promote) {
                    component.srcCommitHash = commitHashMap[component.name]
                    if (!component.srcCommitHash) {
                        componentsMissingMsg += "${component.id} NOT FOUND IN ${projectInfo.deployFromNamespace}"
                    }
                }
            }

            if (componentsMissingMsg.size() > 1) {
                loggingUtils.errorBanner(componentsMissingMsg)
            }
        }

        stage('Checkout all component repositories') {
            loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES:", projectInfo.componentsToPromote.collect { it. name }.join(', '))

            projectInfo.components.each { component ->
                if (component.promote) {
                    dir(component.workDir) {
                        projectUtils.cloneGitRepo(component, component.srcCommitHash)

                        component.previousDeploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.deployFromEnv)
                        component.deploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.deployToEnv)

                        component.deployBranchExists = sh(returnStdout: true, script: "git show-ref refs/remotes/origin/${component.deploymentBranch} || : | tr -d '[:space:]'")
                        component.deployBranchExists = !component.deployBranchExists.isEmpty()

                        def ref = component.deployBranchExists ? component.deploymentBranch : component.previousDeploymentBranchName
                        if (ref) {
                            sh "git checkout ${ref}"
                        }

                        component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
                    }
                }
            }
        }

        stage("promote images") {
            loggingUtils.echoBanner("PROMOTE IMAGES FROM ${projectInfo.deployFromNamespace} ENVIRONMENT TO ${projectInfo.deployToNamespace} ENVIRONMENT FOR:",
                                    projectInfo.componentsToPromote.collect { it. name }.join(', '))

            withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployFromEnv),
                                              usernameVariable: 'FROM_IMAGE_REGISTRY_USERNAME',
                                              passwordVariable: 'FROM_IMAGE_REGISTRY_PWD'),
                             usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                              usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                              passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
            {
                projectInfo.componentsToPromote.each { component ->
                    def promoteTag = "${projectInfo.deployToEnv}-${component.srcCommitHash}"
                    def copyImage =
                        shCmd.copyImage(projectInfo.ENV_FROM,
                                                'FROM_IMAGE_REGISTRY_USERNAME',
                                                'FROM_IMAGE_REGISTRY_PWD',
                                                component.id,
                                                projectInfo.deployFromEnv,
                                                projectInfo.ENV_TO,
                                                'TO_IMAGE_REGISTRY_USERNAME',
                                                'TO_IMAGE_REGISTRY_PWD',
                                                component.id,
                                                promoteTag)

                    def tagImage = shCmd.tagImage(projectInfo.ENV_TO,
                                                  'TO_IMAGE_REGISTRY_USERNAME',
                                                  'TO_IMAGE_REGISTRY_PWD',
                                                  component.id,
                                                  promoteTag,
                                                  projectInfo.deployToEnv)

                    def msg = "${component.id} image promoted and tagged as ${promoteTag} and ${projectInfo.deployToEnv}"
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
                                     projectInfo.componentsToPromote.collect { it. name }.join(', '))

            projectInfo.components.each { component ->
                if (component.promote && !component.deployBranchExists) {
                    dir(component.workDir) {
                        withCredentials([sshUserPrivateKey(credentialsId: component.gitDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                            sh """
                                ${shCmd.echo  "-> Creating Deployment Branch for the image ${component.id}: ${component.deploymentBranch}"}
                                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY',
                                                     "git branch ${component.deploymentBranch}",
                                                     "git push origin ${component.deploymentBranch}:${component.deploymentBranch}")}
                            """
                        }
                    }
                }
                else if (component.promote) {
                    echo "-> Deployment Branch already exists for the image ${component.id}: ${component.deploymentBranch}"
                }
            }
        }
    }

    if (projectInfo.componentsToPromote) {
        deployComponents(projectInfo: projectInfo,
                            components: projectInfo.componentsToPromote,
                            componentsToRemove: projectInfo.componentsToRemove,
                            imageTag: projectInfo.deployToEnv)
    }
    else {
        deployComponents(projectInfo: projectInfo, componentsToRemove: projectInfo.componentsToRemove)
    }
}
