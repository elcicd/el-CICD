/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def getProjectRefreshMap(def includeTeams, def includeProjects) {
    includeTeams = includeTeams ? ~/${includeTeams}/ : ''
    includeProjects = includeProjects ? ~/${includeProjects}/ : ''

    def projectRefreshMap = [:]
    dir (el.cicd.PROJECT_DEFS_DIR) {
        def allProjectFiles = []
        allProjectFiles.addAll(findFiles(glob: "**/*.json"))
        allProjectFiles.addAll(findFiles(glob: "**/*.yml"))
        allProjectFiles.addAll(findFiles(glob: "**/*.yaml"))

        extPattern = ~/[.].*/
        allProjectFiles.each { file ->
            path = file.path
            if (path.contains('/')) {
                projectAndFile = path.split('/')
                teamId = projectAndFile[0]
                projectId = projectAndFile[1]
                if ((!includeTeams || includeTeams.matcher.matches(teamId)) &&
                    (!includeProjects || includeProjects.matcher.matches(projectId)))
                {

                    projectList = projectRefreshMap.get(projectAndFile[0]) ?: []
                    projectList.add(projectAndFile[1].minus(extPattern))
                    projectRefreshMap.put(projectAndFile[0], projectList)
                }
            }
        }
        
        return removeUndeployedTeamsAndProjects(projectRefreshMap)
    }
}

def confirmTeamServersRefresh(def projectRefreshMap, def args) {
    def teamList = []
    projectRefreshMap.each { team, projectList ->
        teamList.add("${team}: ${projectList}")
    }
    
    def msg = loggingUtils.createBanner(
        teamList,
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        'THE ABOVE LIST OF TEAMS AND PROJECTS WILL HAVE THE FOLLOWING DONE:',
        '',
        "Team Servers ${args.refreshTeamServers ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        "Project pipelines ${args.refreshPipelines ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        "Project SDLC environments ${args.refreshEnvironments ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        "Project credentials ${args.refreshCredentials ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        '',
        args.confirmBeforeRefreshing ? "Do you wish to continue?" : '--> USER CONFIRMATION NOT REQUESTED: SKIPPING'
    )

    if (args.confirmBeforeRefreshing) {
        jenkinsUtils.displayInputWithTimeout(msg, args)
    }
    else {
        echo msg
    }
}

def gatherAllProjectsInformation(def projectRefreshMap, def teamInfoList, def projectInfoList) {
    projectRefreshMap.each { teamId, projectList ->
        def teamInfo = projectInfoUtils.gatherTeamInfo(teamId)
        teamInfoList.add(teamInfo)
        projectInfoList.addAll(projectList.collect { projectId ->
            return projectInfoUtils.gatherProjectInfo(teamInfo, projectId)
        })
    }
    
    echo "teamInfoList: ${teamInfoList} / projectInfoList: ${projectInfoList.size()}"
}

def refreshProjectPipelines(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING PIPELINES NOT REQUESTED: SKIPPING"
    }

    def refreshProjectInfoList = shouldRefresh ? projectInfoList : []
    concurrentUtils.runParallelStages("Refresh pipelines", refreshProjectInfoList) { projectInfo ->
        echo "--> REFRESHING PIPELINES FOR PROJECT ${projectInfo.teamInfo.id}:${projectInfo.id}"

        onboardProjectUtils.setupProjectPipelines(projectInfo)
    }
}

def refreshProjectSdlcEnvironments(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING SDLC ENVIRONMENTS NOT REQUESTED: SKIPPING"
    }

    def refreshProjectInfoList = shouldRefresh ? projectInfoList : []
    concurrentUtils.runParallelStages("Refresh SDLC environments", refreshProjectInfoList) { projectInfo ->
        echo "--> REFRESHING SDLC ENVIRONMENTS FOR PROJECT ${projectInfo.teamInfo.id}:${projectInfo.id}"

        onboardProjectUtils.setProjectSdlc(projectInfo)
    }
}

def refreshProjectCredentials(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING PROJECT CREDENTIALS NOT REQUESTED: SKIPPING"
    }

    def refreshProjectInfoList = shouldRefresh ? projectInfoList : []
    concurrentUtils.runParallelStages("Refresh project credentials", refreshProjectInfoList) { projectInfo ->
        echo "--> REFRESHING PROJECT CREDENTIALS FOR PROJECT ${projectInfo.teamInfo.id}:${projectInfo.id}"

        onboardProjectUtils.setupProjectCredentials(projectInfo)
    }
}

def refreshCredentials(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING PROJECT CREDENTIALS NOT REQUESTED: SKIPPING"
    }

    def refreshProjectModuleList = []
    if (shouldRefresh) {
        projectInfoList.each { projectInfo ->
            refreshProjectModuleList.addAll(projectInfo.modules)
        }
    }
    
    loggingUtils.echoBanner("ADD DEPLOY KEYS TO EACH GIT REPO FOR EACH PROJECT IN EACH TEAM")
    projectUtils.createNewGitDeployKeysForProject(refreshProjectModuleList)

    loggingUtils.echoBanner("ADD WEBHOOKS TO EACH GIT REPO FOR EACH PROJECT IN EACH TEAM")
    refreshProjectModuleList = refreshProjectModuleList.findAll {
        it.projectInfo.projectModule != it
    }
    projectUtils.createNewGitWebhooksForProject(refreshProjectModuleList)
}

def refreshTeamCicdServers(def teamInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo '--> REFRESH TEAM SERVERS NOT REQUESTED: SKIPPING'
    }
    
    def refreshTeamServerList = shouldRefresh ? teamInfoList : []
    concurrentUtils.runParallelStages('Refresh team servers', refreshTeamServerList) { teamInfo -> 
        echo "--> REFRESH TEAM ${teamInfo.id} SERVER"
        onboardProjectUtils.setupTeamCicdServer(teamInfo)

        echo "--> SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION FOR ALL TEAM ${teamInfo.id} PROJECTS"
        projectUtils.syncJenkinsPipelines(teamInfo)
    }
}

def removeUndeployedTeamsAndProjects(def projectRefreshMap) {
    teamNamespaceList = projectRefreshMap.keySet().collect { "${it}-${el.cicd.EL_CICD_MASTER_NAMESPACE}" }.join(' ')
    def namespaceScript = "oc get namespaces --ignore-not-found --no-headers ${teamNamespaceList} -o custom-columns=:metadata.name"
    projectNames = sh(returnStdout: true, script: namespaceScript).split("\n").collect { it - "-${el.cicd.EL_CICD_MASTER_NAMESPACE}" }
    projectRefreshMap = projectRefreshMap.findAll { team, projectList ->
        projectNames.contains(team) && projectList
    }
    
    projectRefreshMap.collectEntries { team, projectList ->
        def chartNameList = projectList.collect{ "${it}-${el.cicd.CREDENTIALS_POSTFIX}" }.join('|')
        def helmScript = "helm list -q -n ${team}-${el.cicd.EL_CICD_MASTER_NAMESPACE} --filter '${chartNameList}'"
        projectNames = sh(returnStdout: true, script: helmScript).split("\n").collect { it - "-${el.cicd.CREDENTIALS_POSTFIX}" }
        return [team: projectNames]
    }
    
    projectRefreshMap = projectRefreshMap.findAll { team, projectList -> projectList }
    
    return projectRefreshMap
}