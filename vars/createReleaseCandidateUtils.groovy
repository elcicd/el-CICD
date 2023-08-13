/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

 def verifyVersionTagDoesNotExistInScm(def projectInfo) {
    projectInfo.components.each { component ->
        dir(component.workDir) {
            withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                def tagExists = sh(returnStdout: true,
                                   script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git ls-remote ${component.scmRepoUrl} --tags ${projectInfo.versionTag}"))
                if (tagExists) {
                    loggingUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.versionTag} exists in SCM, and CANNOT be reused")
                }
            }
        }
    }
 }

 def verifyReleaseCandidateImagesDoNotExistInImageRegistry(def projectInfo) {
    def imageExists = true
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                     usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                     passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
        imageExists = projectInfo.components.find { component ->
            def verifyImageCmd = shCmd.verifyImage(projectInfo.PRE_PROD_ENV,
                                                   'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                                   'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                    component.id,
                                                    projectInfo.versionTag)

            return sh(returnStdout: true, script: verifyImageCmd)
        }
    }

    if (imageExists) {
        def msg = "Version tag exists in pre-prod image registry for ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused"
        loggingUtils.errorBanner("CREATING RELEASE CANDIDATE VERSION ${projectInfo.versionTag} FAILED: ", msg)
    }
 }

 def selectReleaseCandidateComponents(def projectInfo, def args) {
    def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.component}{":"}{.data.src-commit-hash}{" "}'
    def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.preProdNamespace}"
    def msNameHashData = sh(returnStdout: true, script: script)

    def componentsAvailable = projectInfo.components.findAll { component ->
        msNameHashData.find("${component.name}:[0-9a-z]{7}")
    }

    def inputs = componentsAvailable.collect { component ->
        booleanParam(name: component.name, defaultValue: component.status, description: "status: ${component.status}")
    }

    if (!inputs) {
        loggingUtils.errorBanner("NO COMPONENTS AVAILABLE TO TAG!")
    }

    def title = "Select components currently deployed in ${projectInfo.preProdNamespace} to tag as Release Candidate ${projectInfo.versionTag}"
    def cicdInfo = jenkinsUtils.displayInputWithTimeout(title, args, inputs)

    projectInfo.componentsInReleaseCandidate = componentsAvailable.findAll { component ->
        def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
        component.promote = answer ? true : false

        return component.promote
    }

    if (!projectInfo.componentsToTag) {
        loggingUtils.errorBanner("NO COMPONENTS SELECTED TO TAG!")
    }
 }

 def confirmReleaseCandidateManifest(def projectInfo, def args) {
    def promotionNames = projectInfo.componentsInReleaseCandidate.collect { "${it.name}" }
    def removalNames = projectInfo.components.findAll{ !it.promote }.collect { "${it.name}" }

    def msg = loggingUtils.createBanner(
        "CONFIRM CREATION OF COMPONENT MANIFEST FOR RELEASE CANDIDATE VERSION ${projectInfo.versionTag}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> SELECTED COMPONENTS IN THIS VERSION WILL HAVE THEIR',
        "   - ${projectInfo.preProdEnv} IMAGES TAGGED AS ${projectInfo.versionTag} IN THE PRE-PROD IMAGE REGISTRY",
        "   - DEPLOYMENT BRANCHES [deployment-${projectInfo.preProdEnv}-<src-commit-has>] TAGGED AS ${projectInfo.versionTag}-<src-commit-hash>:",
        '',
        promotionNames,
        '',
        '---',
        '',
        '-> IGNORED COMPONENTS IN THIS VERSION:',
        '   - Will NOT be deployed in prod',
        '   - WILL BE REMOVED FROM prod if currently deployed and this version is promoted',
        '',
        removalNames,
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        "WARNING: A Release Candidate CAN ONLY BE CREATED ONCE with version ${projectInfo.versionTag}",
        '',
        'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
        '',
        "Should Release Candidate ${projectInfo.versionTag} be created?",
    )

    jenkinsUtils.displayInputWithTimeout(msg, args)
 }

 def createReleaseCandidate(def projectInfo) {
    concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.componentsInReleaseCandidate) { component ->
        def gitReleaseCandidateTag = "${projectInfo.versionTag}-${component.srcCommitHash}"
        def tagImageCmd = shCmd.tagImage(projectInfo.PRE_PROD_ENV,
                                            'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                            'PRE_PROD_IMAGE_REGISTRY_PWD',
                                            component.id,
                                            projectInfo.preProdEnv,
                                            projectInfo.versionTag)
        component.deploymentBranch = projectInfoUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.preProdEnv)

        dir(component.workDir) {
            withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY'),
                             usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                                              usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                              passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
                sh """
                    git checkout ${component.deploymentBranch}
                    CUR_BRANCH=`git rev-parse --abbrev-ref HEAD`
                    ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git tag ${gitReleaseCandidateTag}", "git push --tags")}
                    ${shCmd.echo ''}
                    ${shCmd.echo "--> Git repo '${component.scmRepoName}' tag created in branch '\${CUR_BRANCH}' as '${gitReleaseCandidateTag}'"}

                    ${tagImageCmd}
                    ${shCmd.echo "--> Image ${component.id}:${projectInfo.preProdEnv} tagged as ${component.id}:${projectInfo.versionTag}"}
                    ${shCmd.echo ''}
                """
            }
        }
    }
 }