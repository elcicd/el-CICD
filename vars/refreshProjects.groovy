/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def includeTeams = args.includeTeams
    def includeProjects = args.includeProjects
    def refreshTeamServers = args.refreshTeamServers
    def refreshPipelines = args.refreshPipelines
    def refreshEnvironments = args.refreshEnvironments
    def refreshCredentials = args.refreshCredentials
    def confirmBeforeRefreshing = args.confirmBeforeRefreshing
    
    def projectRefreshMap
    stage('Gather all projects') {
        loggingUtils.echoBanner('GATHER LIST OF PROJECTS FOR REFRESH')
        
        projectRefreshMap = refreshProjectsUtils.getProjectRefreshMap(includeTeams, includeProjects)
    }
    
    stage('Confirm projects to be refreshed') {
        if (confirmBeforeRefreshing) {
            refreshProjectsUtils.confirmProjectsForRefresh(projectRefreshMap, args)
        }
        else {
            echo '--> USER CONFIRMATION NOT REQUESTED; SKIPPING'
        }
    }

    stage('Update Team Servers') {
        if (refreshTeamServers) {
        }
        else {
            echo '--> REFRESH TEAM SERVERS NOT REQUESTED; SKIPPING'
        }
        loggingUtils.echoBanner("DEPLOY PIPELINES FOR PROJECT ${projectInfo.id}")
        onboardTeamCicdServerUtils.setupProjectPipelines(projectInfo)

        loggingUtils.echoBanner("SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION")
        projectUtils.syncJenkinsPipelines(projectInfo)
    }
    
    // stage("refresh each project's CICD") {
    //     cicdProjects.each { projectInfo ->
    //         onboardTeamCicdServerUtils.setupProjectCicdResources(projectInfo)
    //     }
    // }
    
    // stage('refresh every deployed projects credentials') {
    //     cicdProjects.each { projectInfo ->
    //         manageCicdCredentials([projectInfo: projectInfo, isNonProd: true])
    //     }
    // }
    
    // stage('sync all Jenkins pipelines') {
    //     if (projectInfos) {
    //         parallel(
    //             firstBatch: {
    //                 stage('synching first batch of CICD servers') {
    //                     syncPipelines(projectInfos)
    //                 }
    //             },
    //             secondBatch: {
    //                 stage('synching second batch of CICD servers') {
    //                     syncPipelines(projectInfos)
    //                 }
    //             },
    //             thirdBatch: {
    //                 stage('synching third batch of CICD servers') {
    //                     syncPipelines(projectInfos)
    //                 }
    //             },
    //             fourthBatch: {
    //                 stage('synching fourth batch of CICD servers') {
    //                     syncPipelines(projectInfos)
    //                 }
    //             },
    //             fifthBatch: {
    //                 stage('synching fifth batch of CICD servers') {
    //                     syncPipelines(projectInfos)
    //                 }
    //             }
    //         )
    //     }
    // }
}
