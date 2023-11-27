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
    
    teamInfoList = []
    projectInfoList = []
    
    projectRefreshMap.each { teamId, projectList ->
        def teamInfo = projectInfoUtils.gatherTeamInfo(projectData.key)
        teamInfoList.add(teamInfo)
        projectInfoList += projectList.collect { projectId ->
            return projectInfoUtils.gatherProjectInfoStage(teamInfo, projectId)
        }
    }
    
    refreshProjects(projectInfoList, refreshPipelines, 'pipelines')
    
    refreshProjects(projectInfoList, refreshEnvironments, 'SDLC environments')
    
    refreshProjects(projectInfoList, refreshCredentials, 'credentials')

    if (!refreshTeamServers) {
        echo '--> REFRESH TEAM SERVERS NOT REQUESTED; SKIPPING'
    }
    
    def refreshTeamServerList = refreshTeamServers ? teamInfoList : []
    def refreshTeamServerStages = concurrentUtils.createParallelStages('Refresh team servers', refreshTeamServerList) { teamInfo -> 
        echo "--> REFRESH TEAM ${teamInfo.teamId} SERVER"
        onboardProjectUtils.setupTeamCicdServer(teamInfo.teamId)

        echo "--> SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION FOR TEAM ${teamInfo.teamId}")
        projectUtils.syncJenkinsPipelines(teamInfo)
    }

    parallel(refreshTeamServerStages)
}
