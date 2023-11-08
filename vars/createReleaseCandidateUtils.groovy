/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def verifyVersionTagValidSemver(projectInfo) {    
    if (!projectInfo.releaseVersion.matches(projectInfoUtils.SEMVER_REGEX)) {
        loggingUtils.errorBanner('STRICT SEMVER VALIDATION IS ENABLED',
                                 '',
                                 "${projectInfo.releaseVersion} is NOT a valid SemVer",
                                 '',
                                 'Disable strict SemVer validation or see https://semver.org/ for more information')
    }
    else {
        echo "--> Version Tag ${projectInfo.releaseVersion} confirmed valid"
    }
 }

 def verifyVersionTagDoesNotExistInScm(def projectInfo) {
    projectInfo.components.each { component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            versionTagScript = /git ls-remote --tags ${component.scmRepoUrl} | grep "${projectInfo.releaseVersion}-[a-z0-9]\{7\}" || :/
            def tagExists = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript))
            if (tagExists) {
                loggingUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.releaseVersion} exists in SCM (${}), and CANNOT be reused")
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
                                                    projectInfo.releaseVersion)

            return sh(returnStdout: true, script: verifyImageCmd)
        }
    }

    if (imageExists) {
        def msg = "Version tag exists in pre-prod image registry for ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused"
        loggingUtils.errorBanner("CREATING RELEASE CANDIDATE VERSION ${projectInfo.releaseVersion} FAILED: ", msg)
    }
 }

 def selectReleaseCandidateComponents(def projectInfo, def args) {
    def componentsAvailable = getAvailableComponents(projectInfo)

    if (!componentsAvailable) {
        loggingUtils.errorBanner("NO COMPONENTS AVAILABLE TO TAG!")
    }

    def inputs = componentsAvailable.collect { component ->
        booleanParam(name: component.name, defaultValue: component.status, description: "status: ${component.status}")
    }

    def title = "Select components currently deployed in ${projectInfo.preProdNamespace} to tag as Release Candidate ${projectInfo.releaseVersion}"
    def cicdInfo = jenkinsUtils.displayInputWithTimeout(title, args, inputs)

    projectInfo.releaseCandidateComponents = componentsAvailable.findAll { component ->
        def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
        component.promote = answer ? true : false

        return component.promote
    }

    if (!projectInfo.releaseCandidateComponents) {
        loggingUtils.errorBanner("NO COMPONENTS SELECTED TO TAG!")
    }
 }

 def getAvailableComponents(def projectInfo) {
    def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.component}{":"}{.data.src-commit-hash}{" "}'
    def script = "oc get cm -l el-cicd.io/projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.preProdNamespace}"

    def msNameHashData = sh(returnStdout: true, script: script).split(' ')
    msNameHashMap = [:]
    msNameHashData.each {
        def kv = it.split(':')
        if (kv) {
            msNameHashMap.put(kv[0], kv[1])
        }
    }

    return projectInfo.components.findAll { component ->
        if (msNameHashMap.keySet().contains(component.name)) {
            component.srcCommitHash = msNameHashMap[component.name]
            return true
        }
    }
 }

 def confirmReleaseCandidateManifest(def projectInfo, def args) {
    def promotionNames = projectInfo.releaseCandidateComponents.collect { "${it.name}" }
    def removalNames = projectInfo.components.findAll{ !it.promote }.collect { "${it.name}" }

    def sch = '<src-commit-hash>'
    def msg = loggingUtils.createBanner(
        "CONFIRM CREATION OF COMPONENT MANIFEST FOR RELEASE CANDIDATE VERSION ${projectInfo.releaseVersion}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> SELECTED COMPONENTS IN THIS VERSION WILL HAVE THEIR',
        "   - ${projectInfo.preProdEnv} IMAGES TAGGED AS ${projectInfo.releaseVersion} IN THE PRE-PROD IMAGE REGISTRY",
        "   - DEPLOYMENT BRANCHES [deployment-${projectInfo.preProdEnv}-${sch}] TAGGED AS ${projectInfo.releaseVersion}-<src-commit-hash>:",
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
        "WARNING: A Release Candidate CAN ONLY BE CREATED ONCE with version ${projectInfo.releaseVersion}",
        '',
        'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
        '',
        "Should Release Candidate ${projectInfo.releaseVersion} be created?",
    )

    jenkinsUtils.displayInputWithTimeout(msg, args)
 }

 def createReleaseCandidate(def projectInfo) {
    concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.releaseCandidateComponents) { component ->
        def gitReleaseCandidateTag = "${projectInfo.releaseVersion}-${component.srcCommitHash}"
        def tagImageCmd = shCmd.tagImage(projectInfo.PRE_PROD_ENV,
                                         'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                         'PRE_PROD_IMAGE_REGISTRY_PWD',
                                         component.id,
                                         projectInfo.preProdEnv,
                                         projectInfo.releaseVersion)
        component.deploymentBranch = projectInfoUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.preProdEnv)

        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY'),
                            usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                                             usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                             passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
            sh """
                git checkout ${component.deploymentBranch}
                CUR_BRANCH=\$(git rev-parse --abbrev-ref HEAD)
                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git tag ${gitReleaseCandidateTag}", "git push --tags")}
                ${shCmd.echo ''}
                ${shCmd.echo "--> Git repo '${component.scmRepoName}' tag created in branch '\${CUR_BRANCH}' as '${gitReleaseCandidateTag}'"}

                ${tagImageCmd}
                ${shCmd.echo "--> Image ${component.id}:${projectInfo.preProdEnv} tagged as ${component.id}:${projectInfo.releaseVersion}"}
                ${shCmd.echo ''}
            """
        }
    }
 }