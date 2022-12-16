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
        loggingUtils.echoBanner("VERIFY IMAGE(S) DO NOT EXIST IN  ${projectInfo.preProdEnv} REPOSITORY AS ${projectInfo.releaseCandidateTag}")

        if (projectInfo.releaseCandidateTag.startsWith(el.cicd.RELEASE_VERSION_PREFIX)) {
           loggingUtils.errorBanner("Release Candidate tags cannot start with '${el.cicd.RELEASE_VERSION_PREFIX}'.",
                        "'${el.cicd.RELEASE_VERSION_PREFIX}' will prefix Release Versions when Release Candidates are promoted.")
        }

        def imageExists = true
        withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         variable: 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN')]) {
            imageExists = projectInfo.components.find { component ->
                def verifyImageCmd =
                    shCmd.verifyImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.releaseCandidateTag)
                return sh(returnStdout: true, script: verifyImageCmd)
            }
        }

        if (imageExists) {
            loggingUtils.errorBanner("PRODUCTION MANIFEST FOR RELEASE CANDIDATE FAILED for ${projectInfo.releaseCandidateTag}:",
                                      "Version tag exists for project ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused")
        }
    }

    stage('Clone component configuration repositories for components images') {
        loggingUtils.echoBanner("VERIFY VERSION TAG DOES NOT EXIST IN SCM")

        projectInfo.components.each { component ->
            dir(component.workDir) {
                projectUtils.cloneGitRepo(component, projectInfo.scmBranch)

                def gitTagCheck = "git tag --list '${projectInfo.releaseCandidateTag}-*' | wc -l | tr -d '[:space:]'"
                versionTagExists = sh(returnStdout: true, script: gitTagCheck) != '0'
                if (versionTagExists) {
                    loggingUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.releaseCandidateTag} exists, and cannot be reused")
                }
            }
        }
    }

    stage ('Select components to tag as release candidate') {
        loggingUtils.echoBanner("SELECT COMPONENTS TO TAG AS RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}")

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

        def cicdInfo = input( message: "Select components to tag as Release Candidate ${projectInfo.releaseCandidateTag}", parameters: inputs)

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

    stage('Verify selected images exist in pre-prod for promotion') {
        loggingUtils.echoBanner("CONFIRM SELECTED IMAGES EXIST IN PRE-PROD FOR PROMOTION TO PROD")

        def imageExists = true
        withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         variable: 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN')]) {
            def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
            imageMissing = projectInfo.componentsToTag.find { component ->
                def verifyImageCmd =
                    shCmd.verifyImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.preProdEnv)
                return !sh(returnStdout: true, script: "${verifyImageCmd}")
            }
        }

        if (imageMissing) {
            loggingUtils.errorBanner("IMAGE NOT FOUND: one or more images do not exist in ${projectInfo.preProdEnv} for tagging")
        }
    }

    stage('Confirm production manifest for release version') {
        def promotionNames = projectInfo.componentsToTag.collect { it.name }.join(' ')
        def removalNames = projectInfo.components.findAll{ !it.promote }.collect { it.name }.join(' ')

        def msg = loggingUtils.createBanner(
            "CONFIRM CREATION OF COMPONENT MANIFEST FOR RELEASE CANDIDATE VERSION ${projectInfo.releaseCandidateTag}",
            '',
            '===========================================',
            '',
            'Creating this Release Candidate will result in the following actions:',
            '',
            "-> Release Candidate Tag: ${projectInfo.releaseCandidateTag}",
            promotionNames,
            '',
            '-> THE FOLLOWING COMPONENTS WILL BE MARKED FOR REMOVAL FROM PROD:',
            removalNames,
            '',
            '===========================================',
            '',
            'PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION',
            '',
            "Should the Release Candidate ${projectInfo.releaseCandidateTag} be created?"
        )

        input(msg)
    }

    stage('Tag all images') {
        loggingUtils.echoBanner("TAG ALL RELEASE CANDIDATE IMAGES IN ${projectInfo.preProdEnv} AS ${projectInfo.releaseCandidateTag}")

        withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                         variable: 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN')]) {
            projectInfo.componentsToTag.each { component ->
                def tagImageCmd =
                    shCmd.tagImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.preProdEnv, projectInfo.releaseCandidateTag)
                sh """
                    ${shCmd.echo ''}
                    ${tagImageCmd}
                    ${shCmd.echo "${component.id}:${projectInfo.preProdEnv} tagged as ${component.id}:${projectInfo.releaseCandidateTag}"}
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
                            ${shCmd.echo "-> Tagging release candidate in '${component.scmRepoName}' in branch '\${CUR_BRANCH}' as '${gitReleaseCandidateTag}'"}
                            ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git tag ${gitReleaseCandidateTag}", "git push --tags")}
                        """
                    }
                }
            }
        }
    }
}