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

        removeUndeployedTeams(projectRefreshMap)
    }

    return projectRefreshMap
}

def removeUndeployedTeams(def projectRefreshMap) {
    teamMasterNamespaces = projectRefreshMap.keySet().collect { "${it}-${el.cicd.EL_CICD_MASTER_NAMESPACE}" }

    teamMasterNamespaces = sh(returnStdout: true, script: """
            oc get namespaces --ignore-not-found --no-headers -o name ${teamMasterNamespaces.join(' ')} | \
                sed -e 's|namespace/||g' -e 's|-${el.cicd.EL_CICD_MASTER_NAMESPACE}||g'
        """).split('\n')

    projectRefreshMap = projectRefreshMap.findAll { teamMasterNamespaces.contains(it.key) }
}

def confirmProjectsForRefresh(def projectRefreshMap, def args) {
    def msgList = []
    projectRefreshMap.each { teamName, projectList ->
        msgList += [
            "${teamName}: ${projectList}",
            '',
        ]
    }

    def msg = loggingUtils.createBanner(
        msgList,
        loggingUtils.BANNER_SEPARATOR,
        '',
        'PLEASE REVIEW THE ABOVE LIST OF TEAMS AND PROJECTS AND THE FOLLOWING CAREFULLY:',
        '',
        "Team Servers ${args.refreshTeamServers ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        "Project pipelines ${args.refreshPipelines ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        "Project SDLC environments ${args.refreshEnvironments ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        "Project credentials ${args.refreshCredentials ? 'WILL' : 'WILL NOT'} BE REFRESHED",
        '',
        "Do you wish to continue?"
    )

    jenkinsUtils.displayInputWithTimeout(msg, args)
}

def refreshProjectPipelines(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING PIPELINES NOT REQUESTED; SKIPPING"
    }

    def refreshProjectInfoList = shouldRefresh ? projectInfoList : []
    def refreshStages = concurrentUtils.createParallelStages("Refresh pipelines", refreshProjectInfoList) { projectInfo ->

        echo "--> REFRESHING PIPELINES FOR PROJECT ${projectInfo.teamInfo.id}:${projectInfo.id}"

        onboardProjectUtils.setupProjectPipelines(projectInfo)
    }

    parallel(refreshStages)
}

def refreshProjectSdlcEnvironments(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING SDLC ENVIRONMENTS NOT REQUESTED; SKIPPING"
    }

    def refreshProjectInfoList = shouldRefresh ? projectInfoList : []
    def refreshStages = concurrentUtils.createParallelStages("Refresh SDLC environments", refreshProjectInfoList) { projectInfo ->
        echo "projectInfo.teamInfo: ${projectInfo.teamInfo}"
        echo "--> REFRESHING SDLC ENVIRONMENTS FOR PROJECT ${projectInfo.teamInfo.id}:${projectInfo.id}"

        onboardProjectUtils.setProjectSdlc(projectInfo)
    }

    parallel(refreshStages)
}

def refreshProjectCredentials(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING SDLC ENVIRONMENTS NOT REQUESTED; SKIPPING"
    }

    def refreshProjectInfoList = shouldRefresh ? projectInfoList : []
    def refreshStages = concurrentUtils.createParallelStages("Refresh Project Crendentials", refreshProjectInfoList) { projectInfo ->
        echo "projectInfo.teamInfo: ${projectInfo.teamInfo}"
        echo "--> REFRESHING PROJECT CREDENTIALS FOR PROJECT ${projectInfo.teamInfo.id}:${projectInfo.id}"

        onboardProjectUtils.setupProjectCredentials(projectInfo)
    }

    parallel(refreshStages)
}

def refreshCredentials(def projectInfoList, def shouldRefresh) {
    if (!shouldRefresh) {
        echo "--> REFRESHING PROJECT CREDENTIALS NOT REQUESTED; SKIPPING"
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
        echo '--> REFRESH TEAM SERVERS NOT REQUESTED; SKIPPING'
    }
    
    def refreshTeamServerList = shouldRefresh ? teamInfoList : []
    def refreshTeamServerStages = concurrentUtils.createParallelStages('Refresh team servers', refreshTeamServerList) { teamInfo -> 
        echo "--> REFRESH TEAM ${teamInfo.id} SERVER"
        onboardProjectUtils.setupTeamCicdServer(teamInfo)

        echo "--> SYNCHRONIZE JENKINS WITH PROJECT PIPELINE CONFIGURATION FOR ALL TEAM ${teamInfo.id} PROJECTS"
        projectUtils.syncJenkinsPipelines(teamInfo)
    }

    parallel(refreshTeamServerStages)
}