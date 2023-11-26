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
                    projectList.add(projectAndFile[1])
                    projectRefreshMap.put(projectAndFile[0], projectList)
                }
            }
        }
        
        echo "${projectRefreshMap}"

        removeUndeployedTeams(projectRefreshMap)
    }

    return projectRefreshMap
}

def removeUndeployedTeams(def projectRefreshMap) {
    teamMasterNamespaces = projectRefreshMap.keySet().collect { "${it}-${el.cicd.EL_CICD_MASTER_NAMESPACE}" }

    teamMasterNamespaces = sh(returnStdOut: true, script: """
            oc get namespaces --ignore-not-found --no-headers -o name ${teamMasterNamespaces.join(' ')} | tr -d 'namespaces/'
        """).split('\n')

    projectRefreshMap.retainAll(teamMasterNamespaces)
}

def confirmProjectsForRefresh(def projectRefreshMap, def args) {
    def msgList = []
    extPattern = ~/[.].*/
    projectRefreshMap.keySet().each { teamName ->
        msgList += [
            '',
            loggingUtils.BANNER_SEPARATOR,
            '',
            "${teamName}: ${projectRefreshMap[(teamName)].collect { it.minus(extPattern) }}",
            '',
            loggingUtils.BANNER_SEPARATOR,
            ''
        ]
    }


    def msg = loggingUtils.createBanner(
        "THE FOLLOWING TEAMS AND THEIR PROJECTS WILL BE REFRESHED IF THEY ARE ALREADY ONBOARDED:",
        msgList,
        'PLEASE CAREFULLY REVIEW THE ABOVE LIST OF TEAMS AND PROJECTS',
        '',
        "Do you wish to continue?"
    )

    jenkinsUtils.displayInputWithTimeout(msg, args)
}