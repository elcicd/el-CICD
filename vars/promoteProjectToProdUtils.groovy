/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
 
def verifyProjectReleaseVersion(def projectIngo) {
    withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
        versionTagScript = /git ls-remote ${projectInfo.projectModule.scmRepoUrl} '${projectInfo.releaseVersion}'/
        scmReleaseVersionBranch = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()
        
        if (scmReleaseVersionBranch) {
            loggingUtils.errorBanner("RELEASE VERSION ${projectInfo.releaseVersion} HAS ALREADY BEEN PROMOTED")
        }
    }    
}

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.componentsToPromote = projectInfo.components.findAll{ component ->
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
        
            if (!projectInfo.componentsToPromote) {
                loggingUtils.errorBanner("RELEASE CANDIDATE ${projectInfo.releaseVersion} NOT FOUND IN SCM")
            }

            return component.releaseCandidateScmTag
        }
    }
}

def checkoutReleaseCandidateRepos(def projectInfo) {
    projectInfo.projectModule.releaseCandidateScmTag = projectInfo.releaseVersion

    def modules = [projectInfo.projectModule]
    modules.addAll(projectInfo.componentsToPromote)

    concurrentUtils.runCloneGitReposStages(projectInfo, modules) { module ->
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

def createReleaseVersion(def projectInfo) {
    def modules = [projectInfo.projectModule]
    modules.addAll(projectInfo.componentsToPromote)
    
    deploymentUtils.setupDeploymentDirs(projectInfo, modules)
    
    dir (projectInfo.projectModule.workDir) {
        projectInfo.componentsToPromote.each { component ->
            sh"""
                mkdir -p charts/${component.scmRepoName}
                cp -R ${component.deploymentDir}/* charts/${component.name}
            """
        }
        
        withCredentials([sshUserPrivateKey(credentialsId: component.repoDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            sh """
                cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/project-values.yaml ./values.yaml
                
                cp ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/kustomize.sh .
            """
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
        projectInfo.componentsToPromote.collect { it.name },
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

def pushReleaseVersion(def projectInfo) {
    withCredentials([sshUserPrivateKey(credentialsId: component.repoDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
        sh """
            ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY',
                                    'git add -A',
                                    "git commit -am 'creating ${projectInfo.id} release version ${projectInfo.releaseVersion}",
                                    "git push -u origin")}
        """
    }
}

dev promoteReleaseCandidateImages(def projectInfo) {
    projectInfo.deployFromEnv = projectInfo.preProdEnv
    projectInfo.ENV_FROM = projectInfo.deployFromEnv.toUpperCase()
    projectInfo.deployToEnv = projectInfo.prodEnv
    projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()

    promoteComponentsUtils.runPromoteImagesStages(projectInfo)
}