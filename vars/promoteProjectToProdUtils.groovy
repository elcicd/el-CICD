/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def verifyProjectReleaseVersion(def projectInfo) {
    withCredentials([sshUserPrivateKey(credentialsId: projectInfo.projectModule.gitDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
        versionTagScript = /git ls-remote ${projectInfo.projectModule.gitRepoUrl} '${projectInfo.releaseVersion}'/
        gitReleaseVersionBranch = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()

        if (gitReleaseVersionBranch) {
            loggingUtils.errorBanner("RELEASE VERSION ${projectInfo.releaseVersion} HAS ALREADY BEEN PROMOTED")
        }
    }
}

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.componentsToPromote = projectInfo.components.findAll{ component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.gitDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            versionTagScript = /git ls-remote --tags ${component.gitRepoUrl} '${projectInfo.releaseVersion}-*'/
            gitRepoTag = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()

            if (gitRepoTag) {
                gitRepoTag = gitRepoTag.substring(gitRepoTag.lastIndexOf('/') + 1)
                def msg = "Release candidate tags in Git must be of the form <RELEASE VERSION>-<SRC-COMMIT_HASH: ${gitRepoTag}"
                assert gitRepoTag ==~ /${projectInfo.releaseVersion}-[\w]{7}/ : msg
                
                echo "--> RELEASE ${projectInfo.releaseVersion} COMPONENT FOUND: ${component.gitRepoName} / ${gitRepoTag}"
                component.releaseCandidateScmTag = gitRepoTag
                component.srcCommitHash = component.releaseCandidateScmTag.split('-').last()
            }
            else {
                echo "--> Release ${projectInfo.releaseVersion} component NOT found: ${component.gitRepoName}"
            }

            return component.releaseCandidateScmTag
        }
    }

    if (!projectInfo.componentsToPromote) {
        loggingUtils.errorBanner("RELEASE CANDIDATE ${projectInfo.releaseVersion} NOT FOUND IN GIT")
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
                ${shCmd.echo('', "--> Creating release branch ${module.releaseCandidateScmTag} in project repo ${projectInfo.projectModule.repoName}")}
                git switch -c ${module.releaseCandidateScmTag}
                 ${shCmd.echo('')}
            fi
        """
    }
}

def createReleaseVersionComponentSubCharts(def projectInfo) {
    deployComponentsUtils.setupDeploymentDirs(projectInfo, projectInfo.componentsToPromote)

    dir (projectInfo.projectModule.workDir) {
        projectInfo.componentsToPromote.each { component ->
            sh"""
                mkdir -p charts/${component.name}
                cp -R ${component.deploymentDir}/* charts/${component.name}
            """
        }
    }
}

def createReleaseVersionUmbrellaChart(def projectInfo) {
    dir (projectInfo.projectModule.workDir) {
        sh """
            cp  ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/.helmignore \
                ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${el.cicd.EL_CICD_POST_RENDER_KUSTOMIZE} .

            helm template --set-string elCicdDefs.VERSION=${projectInfo.releaseVersion} \
                          --set-string elCicdDefs.HELM_REPOSITORY_URL=${el.cicd.EL_CICD_HELM_OCI_REGISTRY} \
                          -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/helm-chart-yaml-values.yaml \
                          ${projectInfo.id} ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart | sed -E '/^#|^---/d' > Chart.yaml
        """
    }
}

def confirmPromotion(def projectInfo, def args) {
    def msgArray = [
        "CONFIRM CREATION OF RELEASE ${projectInfo.releaseVersion}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> ACTIONS TO BE TAKEN:',
        "   - A DEPLOYMENT BRANCH [${projectInfo.releaseVersion}] WILL BE CREATED IN THE GIT REPO ${projectInfo.projectModule.gitRepoName}",
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
    echo "projectInfo.projectModule.gitDeployKeyJenkinsId: ${projectInfo.projectModule.gitDeployKeyJenkinsId}"
    withCredentials([sshUserPrivateKey(credentialsId: projectInfo.projectModule.gitDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
        dir(projectInfo.projectModule.workDir) {
            sh """
                git add -A
                git commit -am 'creating ${projectInfo.id} release version ${projectInfo.releaseVersion}'
                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git push origin ${projectInfo.releaseVersion}:${projectInfo.releaseVersion}")}
            """
        }
    }
}

def promoteReleaseCandidateImages(def projectInfo) {
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                                      usernameVariable: 'FROM_OCI_REGISTRY_USERNAME',
                                      passwordVariable: 'FROM_OCI_REGISTRY_PWD'),
                     usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.prodEnv),
                                      usernameVariable: 'TO_OCI_REGISTRY_USERNAME',
                                      passwordVariable: 'TO_OCI_REGISTRY_PWD')])
    {
        def stageTitle = "Promoting to Prod"
        def copyImageStages = concurrentUtils.createParallelStages(stageTitle, projectInfo.componentsToPromote) { component ->
            loggingUtils.echoBanner("PROMOTING AND TAGGING ${component.name} IMAGE FROM ${projectInfo.preProdEnv} TO ${projectInfo.prodEnv}")
                                    
            def copyImage =
                shCmd.copyImage(projectInfo.PRE_PROD_ENV,
                                'FROM_OCI_REGISTRY_USERNAME',
                                'FROM_OCI_REGISTRY_PWD',
                                component.id,
                                projectInfo.releaseVersion,
                                projectInfo.PROD_ENV,
                                'TO_OCI_REGISTRY_USERNAME',
                                'TO_OCI_REGISTRY_PWD',
                                component.id,
                                projectInfo.releaseVersion)
                                
            def msg = "--> ${component.id} image promoted and tagged as ${projectInfo.releaseVersion}"

            sh  """
                ${copyImage}

                ${shCmd.echo ''}
                ${shCmd.echo msg}
            """
        }

        parallel(copyImageStages)
    }
}