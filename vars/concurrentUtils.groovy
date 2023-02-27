/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utilities for creating and running parallel stages.
 */

def createParallelStages(def stageTitle, def listItems, Closure stageSteps) {
    listItems = listItems.collect()

    def parallelStages = [failFast: true]
    for (int i = 1; i <= (el.cicd.JENKINS_MAX_STAGES as int); i++) {
        def stagePrefix = "STAGE[${i}]"
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
    def cloneStages = createParallelStages("Clone Git Repo(s)", modules) { module ->
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