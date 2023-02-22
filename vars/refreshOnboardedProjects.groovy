/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the refresh-onboarded-projects pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/efresh-credentials -pipeline-template.
 *
 */

def call(Map args) {
    onboardingUtils.init()

    jenkinsRefresh = [:]
    cicdProjects = []
    stage('gather project info for refresh') {
        dir (el.cicd.PROJECT_DEFS_DIR) {
            def allProjectFiles = []
            allProjectFiles.addAll(findFiles(glob: "**/*.json"))
            allProjectFiles.addAll(findFiles(glob: "**/*.js"))
            allProjectFiles.addAll(findFiles(glob: "**/*.yml"))
            allProjectFiles.addAll(findFiles(glob: "**/*.yaml"))
            
            jenkinsNoRefresh = []
            allProjectFiles.each { projectFile ->
                def projectId = projectFile.name.split('[.]')[0]
                def projectInfo = projectUtils.gatherProjectInfoStage(projectId)

                def jenkinsServerExists =
                    sh(returnStdout: true, script: "oc get projects --no-headers --ignore-not-found ${projectInfo.cicdMasterNamespace}")
                    
                if (jenkinsServerExists) {
                    jenkinsRefresh[projectInfo.cicdMasterNamespace] = projectInfo
                    
                    def cicdProjectsExist =
                        sh(returnStdout: true, script: "oc get projects --no-headers --ignore-not-found ${projectInfo.devNamespace}")
                        
                    if (cicdProjectsExist) {
                        cicdProjects += projectInfo
                    }
                    else {
                        echo "WARNING [${projectInfo.id}]: SDLC namespaces NOT FOUND; skipping"
                    }
                }
                else {
                    jenkinsNoRefresh += projectInfo.projectInfo.cicdMasterNamespace
                }
            }
            
            echo "[WARNING] The following SDLC namespaces were not found and CICD server refresh will will be SKIPPED:"
            echo jenkinsNoRefresh.join('\n')
        }
    }
    
    projectInfos = []
    projectInfos.addAll(jenkinsRefresh.values())
    stage('refresh each CICD server') {
        projectInfos.each { projectInfo ->
            onboardingUtils.setupProjectCicdServer(projectInfo)
        }
    }
    
    stage("refresh each project's SDLC") {
        cicdProjects.each { projectInfo ->
            onboardingUtils.setupProjectCicdResources(projectInfo)
        }
    }
    
    stage('refresh every deployed projects credentials') {
        cicdProjects.each { projectInfo ->
            manageCicdCredentials([projectInfo: projectInfo, isNonProd: true])
        }
    }
    
    stage('sync all Jenkins pipelines') {
        if (projectInfos) {
            parallel(
                firstBatch: {
                    stage('synching first batch of CICD servers') {
                        syncPipelines(projectInfos)
                    }
                },
                secondBatch: {
                    stage('synching second batch of CICD servers') {
                        syncPipelines(projectInfos)
                    }
                },
                thirdBatch: {
                    stage('synching third batch of CICD servers') {
                        syncPipelines(projectInfos)
                    }
                },
                fourthBatch: {
                    stage('synching fourth batch of CICD servers') {
                        syncPipelines(projectInfos)
                    }
                },
                fifthBatch: {
                    stage('synching fifth batch of CICD servers') {
                        syncPipelines(projectInfos)
                    }
                }
            )
        }
    }
}

def synchronized getProjectInfo(def projectInfos) {
    if (projectInfos) {
        return projectInfos.remove(0)
    }
}

def syncPipelines(def projectInfos) {
    while (projectInfos) {
        def projectInfo = getProjectInfo(projectInfos)
        if (projectInfo) {
            loggingUtils.echoBanner("SYNCING JENKINS PIPELINES IN ${projectInfo.cicdNamespace}")
            onboardingUtils.syncJenkinsPipelines(projectInfo)
        }
    }
}
