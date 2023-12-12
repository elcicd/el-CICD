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
        refreshProjectsUtils.confirmTeamServersRefresh(projectRefreshMap, args)
    }
    
    teamInfoList = []
    projectInfoList = []
    
    stage('Gather each projects information') {
        loggingUtils.echoBanner("GATHER INFORMATION FOR EACH PROJECTS TO BE REFRESHED")
        
        refreshProjectsUtils.gatherAllProjectsInformation(projectRefreshMap, teamInfoList, projectInfoList)
    }
    
    echo "OUTSIDE teamInfoList: ${teamInfoList} / projectInfoList: ${projectInfoList.size()}"
    
    refreshProjectsUtils.refreshProjectPipelines(projectInfoList, refreshPipelines)
    
    refreshProjectsUtils.refreshProjectSdlcEnvironments(projectInfoList, refreshEnvironments)
    
    refreshProjectsUtils.refreshProjectCredentials(projectInfoList, refreshCredentials)
    
    refreshProjectsUtils.refreshCredentials(projectInfoList, refreshCredentials)
    
    refreshProjectsUtils.refreshTeamCicdServers(teamInfoList, refreshTeamServers)
    
    loggingUtils.echoBanner('REFRESH COMPLETE')
}
