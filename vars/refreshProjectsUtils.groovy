/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def collectrefreshProjectMap(def includeTeams, def includeProjects) {   
    includeTeams = includeTeams ? ~/${includeTeams}/ ? ''
    includeProjects = includeProjects ? ~/${includeProjects}/ ? '' 
    
    def refreshProjectMap = [:]
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
                    (!includeProjects || includeProjects.matcher.matches(projectId))
                {

                    projectList = refreshProjectMap.get(projectAndFile[0]) ?: []
                    projectList.add(projectAndFile[1])
                    refreshProjectMap.put(projectAndFile[0], projectList)
                }
            }
        }
    }
    
    return refreshProjectMap
}

def confirmrefreshProjectMap(def refreshProjectMap) {
    def msgList = []
    refreshProjectMap.keySet().each { teamName ->
        msgList += [
            '',
            loggingUtils.BANNER_SEPARATOR,
            '',
            "${teamName}: ${refreshProjectMap[(teamName)]}",
            '',
            loggingUtils.BANNER_SEPARATOR,
            ''
        ]
    }
    
    
    def msg = loggingUtils.createBanner(
        "THE FOLLOWING PROJECT WILL BE REFRESHED:",
        msgList,
        'PLEASE CAREFULLY REVIEW THE ABOVE LIST OF TEAMS AND PROJECTS',
        '',
        "Do you wish to continue?"
    )

    jenkinsUtils.displayInputWithTimeout(msg, args)
}