/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utilities for creating and running parallel stages.
 */

def createParallelStages(def stageTitle, def listItems, Closure stageSteps) {
    listItems = listItems.collect()

    def parallelStages = [failFast: true]
    for (int i = 1; i <= (el.cicd.JENKINS_MAX_STAGES as int); i++) {
        def stagePrefix = "STAGE ${i}:"
        def stageName = ("${stagePrefix}: ${stageTitle}")
        parallelStages[stageName] = {
            stage(stageName) {
                while (listItems) {
                    def listItem = synchronizedRemoveListItem(listItems)
                    if (listItem) {
                        stageSteps(listItem)
                    }
                }
                
                def stageNum = i
                echo "${stagePrefix}: ${stageTitle} COMPLETE"
            }
        }
    }
    
    return parallelStages
}

def synchronized synchronizedRemoveListItem(def listItems) {
    if (listItems) {
        return listItems.remove(0)
    }
}

def runCloneGitReposStages(def projectInfo, def modules, Closure postProcessing = null) {
    def cloneStages = createParallelStages("Clone Git Repos", modules) { module ->
        dir(module.workDir) {
            loggingUtils.echoBanner("CLONING ${module.scmRepoName}:${projectInfo.scmBranch} FROM SCM")
            
            projectUtils.cloneGitRepo(module, projectInfo.scmBranch)
            
            if (postProcessing) {
                postProcessing(module)
            }
        }
    }

    parallel(cloneStages)
}

def runVerifyImagesInRegistryStages(def projectInfo, def components, def deployEnv, def verifedMsgs, def errorMsgs) {
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(deployEnv),
                                      usernameVariable: 'IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'IMAGE_REGISTRY_PWD')]) {
        def stageTitle = "Verify Image Exists In Previous Registry"
        def verifyImageStages = createParallelStages(stageTitle, components) { component ->
            def verifyImageCmd = shCmd.verifyImage(projectInfo.ENV_FROM,
                                                   'IMAGE_REGISTRY_USERNAME',
                                                   'IMAGE_REGISTRY_PWD',
                                                    component.id,
                                                    deployEnv)

            if (!sh(returnStdout: true, script: "${verifyImageCmd}").trim()) {
                def image = "${component.id}:${deployEnv}"
                errorMsgs << "    ${image} NOT FOUND IN ${deployEnv} (${projectInfo.deployFromNamespace})"
            }
            else {
                def imageRepo = el.cicd["${projectInfo.ENV_FROM}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
                verifedMsgs << "   VERIFIED: ${component.id}:${deployEnv} IN ${imageRepo}"
            }
        }

        parallel(verifyImageStages)
    }
}