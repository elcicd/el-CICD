/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utilities for creating and running parallel stages.
 */

def runParallelStages(def stageTitle, def listItems, Closure stageSteps) {
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
    
    parallel(parallelStages)
}

def synchronized synchronizedRemoveListItem(def listItems) {
    if (listItems) {
        return listItems.remove(0)
    }
}

def runCloneGitReposStages(def projectInfo, def modules, Closure postProcessing = null) {
    loggingUtils.echoBanner("CLONING MODULES FROM GIT:", modules.collect { "${it.gitRepoName}:${projectInfo.gitBranch}" })
    def cloneStages = runParallelStages("Clone Git Repo(s)", modules) { module ->
        projectInfoUtils.cloneGitRepo(module, projectInfo.gitBranch, postProcessing)
    }
}