/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-release-candidate-template
 *
 */

def call(Map args) {
    assert (args.releaseCandidateTag ==~ /[\w][\w.-]*/)

    def projectInfo = args.projectInfo
    projectInfo.releaseCandidateTag = args.releaseCandidateTag

    stage('Verify image(s) with release candidate tags do NOT exist in pre-prod repository') {
        pipelineUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN  ${projectInfo.preProdEnv} REPOSITORY AS ${projectInfo.releaseCandidateTag}")

        if (projectInfo.releaseCandidateTag.startsWith(el.cicd.RELEASE_VERSION_PREFIX)) {
            errorBanner("Release Candidate tags cannot start with '${el.cicd.RELEASE_VERSION_PREFIX}'.",
                        "'${el.cicd.RELEASE_VERSION_PREFIX}' will prefix Release Versions when Release Candidates are promoted.")
        }

        def imageExists = true
        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN')]) {
            def preProdUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]

            def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]
            imageExists = projectInfo.microServices.find { microService ->
                def preProdImageUrl = "docker://${preProdImageRepo}/${microService.id}:${projectInfo.releaseCandidateTag}"

                return sh(returnStdout: true, script: """
                    skopeo inspect --raw --creds ${preProdUserName}:\${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN} ${preProdImageUrl} 2> /dev/null || :
                """)
            }
        }

        if (imageExists) {
            pipelineUtils.errorBanner("PRODUCTION MANIFEST FOR RELEASE CANDIDATE FAILED for ${projectInfo.releaseCandidateTag}:",
                                      "Version tag exists for project ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused")
        }
    }

    stage ('Select microservices to tag as release candidate') {
        pipelineUtils.echoBanner("SELECT MICROSERVICES TO TAG AS RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}")

        def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.microservice}{":"}{.data.src-commit-hash}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.preProdNamespace}"
        def msNameHashData = sh(returnStdout: true, script: script)

        projectInfo.microServices.each { microService ->
            def hashData = msNameHashData.find("${microService.name}:[0-9a-z]{7}")
            if (hashData) {
                microService.releaseCandidateAvailable = true
                microService.srcCommitHash = hashData.split(':')[1]
            }
        }

        def inputs = projectInfo.microServices.findAll {it.releaseCandidateAvailable }.collect { microService ->
            booleanParam(name: microService.name, defaultValue: microService.status, description: "status: ${microService.status}")
        }

        if (!inputs) {
            pipelineUtils.errorBanner("NO MICROSERVICES AVAILABLE TO TAG!")
        }

        def cicdInfo = input( message: "Select microservices to tag as Release Candidate ${projectInfo.releaseCandidateTag}", parameters: inputs)

        projectInfo.microServices.each { microService ->
            def answer = (inputs.size() > 1) ? cicdInfo[microService.name] : cicdInfo
            if (answer) {
                microService.promote = true
            }
        }
    }

    stage('Verify selected images exist in pre-prod for promotion') {
        pipelineUtils.echoBanner("CONFIRM SELECTED IMAGES EXIST IN PRE-PROD FOR PROMOTION TO PROD")

        def imageExists = true
        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN')]) {
            def preProdUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]

            def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]
            imageMissing = projectInfo.microServices.find { microService ->
                if (microService.promote) {
                    def preProdImageUrl = "docker://${preProdImageRepo}/${microService.id}:${projectInfo.preProdEnv}"

                    return !sh(returnStdout: true, script: """
                        skopeo inspect --raw --creds ${preProdUserName}:\${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN} ${preProdImageUrl} 2> /dev/null || :
                    """)
                }
            }
        }

        if (imageMissing) {
            pipelineUtils.errorBanner("IMAGE NOT FOUND: one or more images do not exist in ${projectInfo.preProdEnv} for tagging")
        }
    }

    stage('Clone microservice configuration repositories for microservices images') {
        pipelineUtils.echoBanner("CLONE ALL MICROSERVICE DEPLOYMENT REPOSITORIES, AND VERIFY VERSION TAG DOES NOT EXIST IN SCM:",
                                 projectInfo.microServices.findAll { it.promote }.collect { it.name }.join(', '))

        projectInfo.microServices.each { microService ->
            dir(microService.workDir) {
                microService.deploymentBranch = pipelineUtils.getNonProdDeploymentBranchName(projectInfo, microService, projectInfo.preProdEnv)
                pipelineUtils.cloneGitRepo(microService, microService.deploymentBranch)

                versionTagExists = sh(returnStdout: true, script: "git tag --list '${projectInfo.releaseCandidateTag}-*' | wc -l | tr -d '[:space:]'") != '0'
                if (versionTagExists) {
                    pipelineUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.releaseCandidateTag} exists, and cannot be reused")
                }
            }
        }
    }

    stage('Confirm production manifest for release version') {
        pipelineUtils.echoBanner("CONFIRM CREATION OF PRODUCTION MANIFEST FOR RELEASE CANDIDATE VERSION ${projectInfo.releaseCandidateTag}")

        def promotionNames = projectInfo.microServices.findAll{ it.promote }.collect { it.name }.join(' ')
        def removalNames = projectInfo.microServices.findAll{ !it.promote }.collect { it.name }.join(' ')

        def msg = pipelineUtils.createBanner(
            'Creating this Release Candidate will result in the following actions:',
            '',
            "-> Release Candidate Tag: ${projectInfo.releaseCandidateTag}",
            promotionNames,
            '',
            '-> THE FOLLOWING MICROSERVICES WILL BE MARKED FOR REMOVAL FROM PROD:',
            removalNames,
            '',
            "Should the Release Candidate ${projectInfo.releaseCandidateTag} be created?"
        )

        input(msg)
    }

    stage('Tag all images') {
        pipelineUtils.echoBanner("TAG ALL RELEASE CANDIDATE IMAGES IN ${projectInfo.preProdEnv} AS ${projectInfo.releaseCandidateTag}")

        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"], variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN')]) {
            def preProdUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]

            def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]
            projectInfo.microServices.find { microService ->
                if (microService.promote) {
                    def preProdImageUrl = "docker://${preProdImageRepo}/${microService.id}:${projectInfo.preProdEnv}"
                    def preProdReleaseCandidateImageUrl = "docker://${preProdImageRepo}/${microService.id}:${projectInfo.releaseCandidateTag}"
                    sh """
                        ${shellEcho ''}
                        skopeo copy --src-tls-verify=false \
                                    --dest-tls-verify=false \
                                    --src-creds ${preProdUserName}:\${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN} \
                                    --dest-creds ${preProdUserName}:\${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN} \
                                    ${preProdImageUrl} \
                                    ${preProdReleaseCandidateImageUrl}
                        ${"${microService.id}:${projectInfo.preProdEnv} tagged as ${microService.id}:${projectInfo.releaseCandidateTag}"}
                    """
                }
            }
        }
    }

    stage('Tag all images and configuration commits') {
        pipelineUtils.echoBanner("TAG GIT DEPLOYMENT COMMIT HASHES ON EACH MICROSERVICE DEPLOYMENT BRANCH FOR RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}")

        projectInfo.microServices.each { microService ->
            if (microService.promote) {
                dir(microService.workDir) {
                    withCredentials([sshUserPrivateKey(credentialsId: microService.gitSshPrivateKeyName, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                        def gitReleaseCandidateTag = "${projectInfo.releaseCandidateTag}-${microService.srcCommitHash}"
                        sh """
                            CUR_BRANCH=`git rev-parse --abbrev-ref HEAD`
                            ${shellEcho "-> Tagging release candidate in '${microService.gitRepoName}' in branch '\${CUR_BRANCH}' as '${gitReleaseCandidateTag}'"}
                            ${sshAgentBash 'GITHUB_PRIVATE_KEY', "git tag ${gitReleaseCandidateTag}", "git push --tags"}
                        """
                    }
                }
            }
        }
    }
}