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

    stage('Verify image(s) with Release Candidate tags do NOT exist in pre-prod image registry') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN PRE-PROD IMAGE REGISTRY AS ${projectInfo.releaseCandidateTag}")

        if (projectInfo.releaseCandidateTag.startsWith(el.cicd.RELEASE_VERSION_PREFIX)) {
           loggingUtils.errorBanner("Release Candidate tags cannot start with '${el.cicd.RELEASE_VERSION_PREFIX}'.",
                        "'${el.cicd.RELEASE_VERSION_PREFIX}' will prefix Release Versions when Release Candidates are promoted.")
        }

        def imageExists = true
        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                         passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
            imageExists = projectInfo.components.find { component ->
                def verifyImageCmd = shCmd.verifyImage(projectInfo.PRE_PROD_ENV,
                                                       'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                                       'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                       component.id,
                                                       projectInfo.releaseCandidateTag)

                return sh(returnStdout: true, script: verifyImageCmd)
            }
        }

        if (imageExists) {
            def msg = "Version tag exists in pre-prod image registry for ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused"
            loggingUtils.errorBanner("PRODUCTION MANIFEST FOR RELEASE CANDIDATE FAILED for ${projectInfo.releaseCandidateTag}:", msg)
        }
    }

    stage('Verify Release Candidate version tag doesn\'t exist in SCM') {
        loggingUtils.echoBanner("VERIFY THE TAG ${projectInfo.releaseCandidateTag} DOES NOT EXIST IN ANY COMPONENT\'S SCM REPOSITORY")

        projectInfo.components.each { component ->
            dir(component.workDir) {
                projectUtils.cloneGitRepo(component, projectInfo.scmBranch)

                def gitTagCheck = "git tag --list '${projectInfo.releaseCandidateTag}-*' | wc -l | tr -d '[:space:]'"
                versionTagExists = sh(returnStdout: true, script: gitTagCheck) != '0'
                if (versionTagExists) {
                    loggingUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.releaseCandidateTag} existsin SCM, and CANNOT be reused")
                }
            }
        }
    }

    stage ('Select components to tag as Release Candidate') {
        loggingUtils.echoBanner("SELECT COMPONENTS TO TAG AS RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}",
                                '',
                                "NOTE: Only components currently deployed in ${projectInfo.preProdNamespace} will be considered")

        def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.component}{":"}{.data.src-commit-hash}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.preProdNamespace}"
        def msNameHashData = sh(returnStdout: true, script: script)

        projectInfo.components.each { component ->
            def hashData = msNameHashData.find("${component.name}:[0-9a-z]{7}")
            if (hashData) {
                component.releaseCandidateAvailable = true
                component.srcCommitHash = hashData.split(':')[1]

                component.deploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.preProdEnv)
                dir(component.workDir) {
                    sh """
                        git checkout ${component.deploymentBranch}
                    """
                }
            }
        }

        projectInfo.componentsAvailable = projectInfo.components.findAll {it.releaseCandidateAvailable }
        def inputs = projectInfo.componentsAvailable.collect { component ->
            booleanParam(name: component.name, defaultValue: component.status, description: "status: ${component.status}")
        }

        if (!inputs) {
            loggingUtils.errorBanner("NO COMPONENTS AVAILABLE TO TAG!")
        }

        def title = "Select components currently deployed in ${projectInfo.preProdNamespace} to tag as Release Candidate ${projectInfo.releaseCandidateTag}"
        def cicdInfo = jenkinsUtils.displayInputWithTimeout(title, inputs)

        projectInfo.componentsToTag = projectInfo.componentsAvailable.findAll { component ->
            def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
            if (answer) {
                component.promote = true
            }

            return component.promote
        }

        if (!projectInfo.componentsToTag) {
            loggingUtils.errorBanner("NO COMPONENTS SELECTED TO TAG!")
        }
    }

    stage('Confirm production manifest for release version') {
        def promotionNames = projectInfo.componentsToTag.collect { it.name }.join('\n')
        def removalNames = projectInfo.components.findAll{ !it.promote }.collect { it.name }.join('\n')

        def msg = loggingUtils.createBanner(
            "CONFIRM CREATION OF COMPONENT MANIFEST FOR RELEASE CANDIDATE VERSION ${projectInfo.releaseCandidateTag}",
            '',
            '===========================================',
            '',
            '-> Creating this Release Candidate will result in the following actions:',
            "   - SELECTED COMPONENT IMAGES WILL BE TAGGED AS ${projectInfo.releaseCandidateTag} IN THE PRE_PROD IMAGE REGISTRY",
            "   - SELECTED COMPONENT SCM REPOS WILL BE TAGGED AS ${projectInfo.releaseCandidateTag} AT THE HEAD OF BRANCH ${component.deploymentBranch}",
            '',
            promotionNames,
            '',
            '---',
            '',
            '-> THE FOLLOWING COMPONENTS WILL BE IGNORED',
            '   IGNORED COMPONENTS IN THIS VERSION:',
            '   - Will NOT be deployed in prod',
            '   - WILL BE REMOVED FROM prod if currently deployed and this version is promoted',
            '',
            removalNames,
            '',
            '===========================================',
            '',
            "WARNING: A Release Candidate CAN ONLY BE CREATED ONCE with version ${projectInfo.releaseCandidateTag}",
            '',
            'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION',
            '',
            "Should Release Candidate ${projectInfo.releaseCandidateTag} be created?"
        )

        displayInputWithTimeout(msg)
    }

    stage('Tag all images') {
        loggingUtils.echoBanner("TAG ALL RELEASE CANDIDATE IMAGES IN ${projectInfo.preProdEnv} AS ${projectInfo.releaseCandidateTag}")

        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                         passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
            projectInfo.componentsToTag.each { component ->
                def tagImageCmd = shCmd.tagImage(projectInfo.PRE_PROD_ENV, 
                                                 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN',
                                                 'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                 component.id,
                                                 projectInfo.preProdEnv,
                                                 projectInfo.releaseCandidateTag)

                def msg = "Image ${component.id}:${projectInfo.preProdEnv} tagged as ${component.id}:${projectInfo.releaseCandidateTag}"
                sh """
                    ${shCmd.echo ''}
                    ${tagImageCmd}
                    ${shCmd.echo(msg)}
                """
            }
        }
    }

    stage('Tag all images and configuration commits') {
        loggingUtils.echoBanner("TAG GIT DEPLOYMENT COMMIT HASHES ON EACH MICROSERVICE DEPLOYMENT BRANCH FOR RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}")

        projectInfo.components.each { component ->
            if (component.promote) {
                dir(component.workDir) {
                    withCredentials([sshUserPrivateKey(credentialsId: component.repoDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                        def gitReleaseCandidateTag = "${projectInfo.releaseCandidateTag}-${component.srcCommitHash}"
                        sh """
                            CUR_BRANCH=`git rev-parse --abbrev-ref HEAD`
                            ${shCmd.echo "-> Tagging Release Candidate in '${component.scmRepoName}' in branch '\${CUR_BRANCH}' as '${gitReleaseCandidateTag}'"}
                            ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git tag ${gitReleaseCandidateTag}", "git push --tags")}
                        """
                    }
                }
            }
        }
    }
}