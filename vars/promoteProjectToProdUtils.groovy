/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.releaseCandidateComps = projectInfo.components.findAll{ component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            versionTagScript = /git ls-remote --tags ${component.scmRepoUrl} '${projectInfo.releaseVersion}-*'/
            scmRepoTag = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()

            if (scmRepoTag) {
                scmRepoTag = scmRepoTag.substring(scmRepoTag.lastIndexOf('/') + 1)
                echo "-> RELEASE ${projectInfo.releaseVersion} COMPONENT FOUND: ${component.scmRepoName} / ${scmRepoTag}"
                component.releaseCandidateScmTag = scmRepoTag
                assert component.releaseCandidateScmTag ==~ /${projectInfo.releaseVersion}-[\w]{7}/ : msg

            }
            else {
                echo "-> Release ${projectInfo.releaseVersion} component NOT found: ${component.scmRepoName}"
            }

            return component.releaseCandidateScmTag
        }
    }
}

def confirmPromotion(def projectInfo, def args) {
    def msgArray = [
        "CONFIRM CREATION OF RELEASE ${projectInfo.releaseVersion}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> ACTIONS TO BE TAKEN:',
        "   - A DEPLOYMENT BRANCH [${projectInfo.releaseVersion}] WILL BE CREATED IN THE SCM REPO ${projectInfo.projectModule.scmRepoName}",
        "   - IMAGES TAGGED AS ${projectInfo.releaseVersion} WILL BE PUSHED TO THE PROD IMAGE REGISTRY",
        '   - COMPONENTS NOT IN THIS RELEASE WILL BE REMOVED FROM ${projectInfo.prodEnv}',
        '',
        '-> COMPONENTS IN RELEASE:',
        projectInfo.releaseCandidateComps.collect { it.name },
    ]

    def compsNotInRelease = projectInfo.components.findAll{ !it.releaseCandidateScmTag }.collect { it.name }
    if (compsNotInRelease) {
        msgArray += [
            '',
            '-> COMPONENTS NOT IN THIS RELEASE:',
            projectInfo.components.findAll{ !it.releaseCandidateScmTag }.collect { it.name },
        ]
    }

    msgArray += [
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        "WARNING: A Release Candidate CAN ONLY BE PROMOTED ONCE",
        '',
        'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
        '',
        "Should Release ${projectInfo.releaseVersion} be created?"
    ]


    def msg = loggingUtils.createBanner(msgArray)

    jenkinsUtils.displayInputWithTimeout(msg, args)
}

def checkoutReleaseCandidateRepos(def projectInfo) {
    projectInfo.projectModule.releaseCandidateScmTag = projectInfo.releaseVersion

    def modules = [projectInfo.projectModule]
    modules.addAll(projectInfo.releaseCandidateComps)

    concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.releaseCandidateComps) { module ->
        sh """
            ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', 'git fetch --all --tags')}
            
            if [[ ! -z \$(git tag -l ${module.releaseCandidateScmTag}) ]]
            then
                git checkout tags/${module.releaseCandidateScmTag}
            else
                git switch -c ${module.releaseCandidateScmTag}
            fi
        """
    }
}

def createReleaseRepo(def projectInfo) {
    deploymentUtils.setupDeploymentDirs(projectInfo, projectInfo.releaseCandidateComps)
    
    dir (${projectInfo.projectModule.workDir}) {
        projectInfo.releaseCandidateComps.each { component ->
            sh"""
                mkdir -p charts/${component.scmRepoName}
                cp -R ${component.deploymentDir}/* charts/${component.scmRepoName}
            """
        }
        
        sh """
            helm template --set-string elCicdDefs.VERSION=${projectInfo.releaseVersion} \
                          --set-string elCicdDefs.HELM_REPOSITORY_URL=${el.cicd.EL_CICD_HELM_REPOSITORY} \
                          -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${chartYamlValues} \
                          elCicdCharts/elCicdChart > Chart.yaml
                          
            helm template --set-string global.elCicdProfiles=${projectInfo.elCicdProfiles} \
                          --set renderValuesYaml=true \
                          elCicdCharts/elCicdChart > values.yaml
        
        """
    }
}