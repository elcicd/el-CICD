/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the component promotion pipeline.
 */
 
def getUserPromotionRemovalSelections(def projectInfo) {
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

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select components to promote and environment to promote from/to:", inputs)

    def promotionEnvs = cicdInfo.promotionEnvs.split(ENV_DELIMITER)
    projectInfo.deployFromEnv = promotionEnvs.first()
    projectInfo.ENV_FROM = projectInfo.deployFromEnv.toUpperCase()
    projectInfo.deployToEnv = promotionEnvs.last()
    projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
    projectInfo.deployFromNamespace = projectInfo.nonProdNamespaces[projectInfo.deployFromEnv]
    projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]

    def promoteOrRemove
    projectInfo.components.each { component ->
        def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
        component.promote = answer == el.cicd.PROMOTE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.PROMOTE)
        component.remove = answer == el.cicd.REMOVE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.REMOVE)

        promoteOrRemove = promoteOrRemove || component.promote != null
        
        if (component.promote) {
            component.previousDeploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.deployFromEnv)
            component.deploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.deployToEnv)
        }
    }

    if (!promoteOrRemove) {
        loggingUtils.errorBanner("NO COMPONENTS SELECTED FOR PROMOTION OR REMOVAL FOR ${projectInfo.deployToEnv}")
    }

    projectInfo.componentsToPromote = projectInfo.components.findAll{ it.promote }
    projectInfo.componentsToRemove = projectInfo.components.findAll{ it.remove }
}

def runVerifyImagesInRegistryStages(def projectInfo, def verifedMsgs, def errorMsgs) {
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployFromEnv),
                                      usernameVariable: 'FROM_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'FROM_IMAGE_REGISTRY_PWD')]) {
        def stageTitle = "Verify Image Exists In Previous Registry"
        def verifyImageStages = concurrrentUtils.createParallelStages(stageTitle, projectInfo.componentsToPromote) { component ->
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
                verifedMsgs << "   VERIFIED: ${component.id}:${projectInfo.deployFromEnv} IN ${imageRepo}"
            }
        }

        parallel(verifyImageStages)
    }
}

def verifyDeploymentsInPreviousEnv(def projectInfo) {
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

def runCloneGitReposStages(def projectInfo, def components) {
    def cloneStages = concurrrentUtils.createParallelStages("Clone Component Repos", components) { component ->
        dir(component.workDir) {
            loggingUtils.echoBanner("CLONING ${component.scmRepoName} REPO FOR PROMOTION")
            
            projectUtils.cloneGitRepo(component, component.srcCommitHash)

            component.deployBranchExists = sh(returnStdout: true, script: "git show-scmBranch refs/remotes/origin/${component.deploymentBranch} || : | tr -d '[:space:]'")
            component.deployBranchExists = !component.deployBranchExists.isEmpty()

            def scmBranch = component.deployBranchExists ? component.deploymentBranch : component.previousDeploymentBranch
            if (scmBranch) {
                sh "git checkout ${scmBranch}"
            }

            component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
        }
    }

    parallel(cloneStages)
}

def runPromoteImagesToNextRegistryStages(def projectInfo) {
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployFromEnv),
                                      usernameVariable: 'FROM_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'FROM_IMAGE_REGISTRY_PWD'),
                     usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                      usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
    {
        def stageTitle = "Promote Image From ${projectInfo.ENV_FROM} to ${projectInfo.ENV_TO}"
        def copyImageStages = concurrrentUtils.createParallelStages(stageTitle, projectInfo.componentsToPromote) { component ->
            loggingUtils.echoBanner("PROMOTING AND TAGGING ${component.name} IMAGE FROM ${projectInfo.deployFromEnv} TO ${projectInfo.deployToEnv}")
                                    
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

            sh  """
                ${copyImage}

                ${shCmd.echo ''}
                ${tagImage}

                ${shCmd.echo ''}
                ${shCmd.echo  "--> ${msg}"}
            """
        }

        parallel(copyImageStages)
    }
}

def createDeploymentBranches(def projectInfo) {
    projectInfo.components.each { component ->
        if (component.promote && !component.deployBranchExists) {
            dir(component.workDir) {
                withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
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