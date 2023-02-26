/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utilities for creating and running parallel stages.
 */

def createParallelStages(def stageTitle, def listItems, Closure stageSteps) {
    listItems = listItems.collect()
    
    def parallelStages = [failFast: true]
    def numStages = Math.min(listItems.size(), (el.cicd.JENKINS_MAX_STAGES as int))
    for (int i = 1; i <= numStages; i++) {
        def stageName = ("STAGE ${i}: ${stageTitle}")
        listItems.each { module ->
            parallelStages[stageName] = {
                stage(stageName) {
                    while (listItems) {
                        def listItem = synchronizedRemoveListItem(listItems)
                        if (listItem) {
                            stageSteps(listItem)
                        }
                    }
                    
                    echo "STAGE ${i}: ${stageTitle} COMPLETE"
                }
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
            loggingUtils.echoBanner("CLONING ${module.scmRepoName} REPO")
            
            projectUtils.cloneGitRepo(module, module.srcCommitHash)
            
            if (postProcessing) {
                postProcessing(module)
            }
        }
    }

    parallel(cloneStages)
}