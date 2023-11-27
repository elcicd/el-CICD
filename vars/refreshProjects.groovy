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
        def teamInfo = projectInfoUtils.gatherTeamInfo(teamId)
        teamInfoList.add(teamInfo)
        projectInfoList += projectList.collect { projectId ->
            def projectInfo = projectInfoUtils.gatherProjectInfoStage(teamInfo, projectId)
        }
    }
    
    refreshProjectsUtils.refreshProjectPipelines(projectInfoList, refreshPipelines)
    
    refreshProjectsUtils.refreshProjectSdlcEnvironments(projectInfoList, refreshEnvironments)
    
    refreshProjectsUtils.refreshCredentials(projectInfoList, refreshCredentials)
    
    refreshProjectsUtils.runTeamCicdServers(teamInfoList, refreshTeamServers)
    
    loggingUtils.echoBanner('ALL TEAMS AND PROJECTS REFRESHED')

}
