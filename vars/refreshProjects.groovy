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
    
    def refreshProjectMap
    stage('Gather all projects') {
        loggingUtils.echoBanner('GATHER LIST OF PROJECTS FOR REFRESH')
        
        refreshProjectMap = refreshProjectsUtils.collectProjectsForRefresh(includeTeams, includeProjects)
    }
    
    stage('Confirm projects to be refreshed') {
        if (confirmBeforeRefreshing) {
            confirmProjectsForRefresh(refreshProjectMap)
        }
        else {
            echo '--> USER CONFIRMATION NOT REQUESTED; SKIPPING'
        }
    }
    
    // projectInfos = []
    // projectInfos.addAll(jenkinsRefresh.values())
    // stage('refresh each CICD server') {
    //     projectInfos.each { projectInfo ->
    //         onboardTeamCicdServerUtils.setupTeamCicdServer(projectInfo)
    //     }
    // }
    
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
