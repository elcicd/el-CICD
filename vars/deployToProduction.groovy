/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the deploy-to-production pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/deploy-to-production-pipeline-template.
 *
 */

def call(Map args) {

    def deployAll = args.deployAll

    elCicdCommons.initialize()

    projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
    projectInfo.releaseCandidateTag = args.releaseCandidateTag.startsWith(el.cicd.RELEASE_VERSION_PREFIX) ? 
        args.releaseCandidateTag.substring(el.cicd.RELEASE_VERSION_PREFIX.length()) : args.releaseCandidateTag
    projectInfo.releaseVersionTag = "${el.cicd.RELEASE_VERSION_PREFIX}${projectInfo.releaseCandidateTag}"
    projectInfo.deployToEnv = projectInfo.prodEnv
    projectInfo.deployToNamespace = projectInfo.prodNamespace

    stage('Gather all git branches, tags, and source commit hashes') {
        pipelineUtils.echoBanner("GATHER ALL GIT BRANCHES, TAGS, AND SOURCE COMMIT HASHES")

        deployToProductionUtils.gatherAllVersionGitTagsAndBranches(projectInfo)

        if (!projectInfo.microServices.find{ it.releaseCandidateGitTag })  {
            pipelineUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
        else if (projectInfo.microServices.find{ it.releaseVersionGitBranch && !it.releaseCandidateGitTag })  {
            pipelineUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD SCM STATE FOR RELEASE",
                                      "RELEASE BRANCH AND TAG NAME(S) MUST MATCH, OR HAVE NO RELEASE BRANCHES",
                                      "Release Tags: ${projectInfo.microServices.collect{ it.releaseCandidateGitTag }}",
                                      "Release Branches: ${projectInfo.microServices.collect{ it.releaseVersionGitBranch }}")
        }
    }

    stage('Verify images are ready for deployment') {
        pipelineUtils.echoBanner("VERIFY PROMOTION AND/OR DEPLOYMENT CAN PROCEED FOR VERSION ${projectInfo.releaseCandidateTag}:",
                                 projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(', '))

        def allImagesExist = true
        def PROMOTION_ENV_FROM = projectInfo.hasBeenReleased ? projectInfo.PROD_ENV : projectInfo.PRE_PROD_ENV
        withCredentials([string(credentialsId: el.cicd["${PROMOTION_ENV_FROM}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageRepoUserNamePwd = el.cicd["${PROMOTION_ENV_FROM}_IMAGE_REPO_USERNAME"] + ":${IMAGE_REPO_ACCESS_TOKEN}"
            def imageRepo = el.cicd["${PROMOTION_ENV_FROM}_IMAGE_REPO"]
            def imageTag = projectInfo.hasBeenReleased ? projectInfo.releaseVersionTag : projectInfo.releaseCandidateTag

            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    def imageUrl = "docker://${imageRepo}/${microService.id}:${imageTag}"

                    def imageFound = sh(returnStdout: true, script: "skopeo inspect --raw --creds ${imageRepoUserNamePwd} ${imageUrl} 2>&1 || :").trim()

                    def msg = imageFound ? "PROMOTION DEPLOYMENT CAN PROCEED FOR ${microService.name}" : "-> ERROR: no image found in image repo: ${imageUrl}"
                    echo msg

                    allImagesExist = allImagesExist && imageFound
                }
            }
        }

        if (!allImagesExist) {
            pipelineUtils.errorBanner("BUILD FAILED: Missing image(s) to deploy in PROD")
        }
    }

    stage('Checkout all microservice repositories') {
        pipelineUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.microServices.each { microService ->
            def refName = microService.releaseVersionGitBranch ?: microService.releaseCandidateGitTag
            if (refName) {
                pipelineUtils.cloneGitRepo(microService, refName)
            }
        }
    }

    stage('Collect deployment hashes; if incremental release, prune unchanged microservices from deployment') {
        pipelineUtils.echoBanner("COLLECT DEPLOYMENT HASHES AND PRUNE UNNECESSARY DEPLOYMENTS")

        def metaInfoRelease = sh(returnStdout: true, script: "oc get cm ${projectInfo.id}-meta-info -o jsonpath='{ .data.release-version }' -n ${projectInfo.prodNamespace} || :")
        projectInfo.microServices.each { microService ->
            if (microService.releaseCandidateGitTag) {
                dir(microService.workDir) {
                    def deploymentCommitHash
                    if (!deployAll && metaInfoRelease == projectInfo.releaseVersionTag) {
                        def depCommitHashScript = "oc get cm ${microService.id}-meta-info -o jsonpath='{ .data.deployment-commit-hash }' -n ${projectInfo.prodNamespace} || :"
                        deploymentCommitHash = sh(returnStdout: true, script: depCommitHashScript)
                    }

                    if (deployAll || !projectInfo.hasBeenReleased || deploymentCommitHash != microService.deploymentCommitHash) {
                        microService.promote = true
                    }
                }
            }
        }
    }

    stage('Confirm release to production') {
        def promotionOrDeploymentMsg = projectInfo.hasBeenReleased ?
            "CONFIRM REDEPLOYMENT OF ${projectInfo.releaseVersionTag}" :
            "CONFIRM PROMOTION AND DEPLOYMENT OF RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}"

        def deployAllMsg = (projectInfo.hasBeenReleased && deployAll) ?
            '-> YOU HAVE ELECTED TO REDEPLOY ALL MICROSERVICES:' :
            '-> Microservices to be deployed:'

        input("""

            ===========================================
            ${promotionOrDeploymentMsg} TO PRODUCTION
            ===========================================

            *******
            -> Microservices included in this release:
            ${projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(', ')}
            *******

            *******
            ${deployAllMsg}
            ${projectInfo.microServices.findAll { it.promote }.collect { it.name }.join(', ')}
            *******

            *******
            -> All other microservices and their associated resources NOT in this release WILL BE REMOVED!
            ${projectInfo.microServices.findAll { !it.releaseCandidateGitTag }.collect { it.name }.join(', ')}
            *******

            ===========================================
            PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION

            ARE YOU SURE YOU WISH TO PROCEED?
            ===========================================
        """)
    }

    stage('Promote images') {
        pipelineUtils.echoBanner("PROMOTE IMAGES TO PROD:", "${projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name } .join(', ')}")

        if (!projectInfo.hasBeenReleased) {
            withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN'),
                             string(credentialsId: el.cicd["${projectInfo.PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'PROD_IMAGE_REPO_ACCESS_TOKEN')])
            {
                def fromUserNamePwd = el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_USERNAME"] + ":${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN}"
                def toUserNamePwd = el.cicd["${projectInfo.PROD_ENV}_IMAGE_REPO_USERNAME"] + ":${PROD_IMAGE_REPO_ACCESS_TOKEN}"
                def skopeoCopyComd = "skopeo copy --src-creds ${fromUserNamePwd} --dest-creds ${toUserNamePwd} --src-tls-verify=false --dest-tls-verify=false"

                def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO"]
                def prodImageRepo = el.cicd["${projectInfo.PROD_ENV}_IMAGE_REPO"]

                projectInfo.microServices.each { microService ->
                    if (microService.releaseCandidateGitTag) {
                        def preProdImageUrl = "${preProdImageRepo}/${microService.id}:${projectInfo.releaseCandidateTag}"
                        def prodImageUrl = "${prodImageRepo}/${microService.id}:${projectInfo.releaseVersionTag}"

                        sh """
                            ${skopeoCopyComd} docker://${preProdImageUrl} docker://${prodImageUrl}

                            ${shellEcho "******",
                                        "${microService.name}: ${projectInfo.releaseCandidateTag} in ${projectInfo.preProdEnv} PROMOTED TO ${projectInfo.releaseVersionTag} in ${projectInfo.prodEnv}",
                                        "******" }
                        """
                    }
                }
            }
        }
        else {
            echo "-> IMAGES HAVE ALREADY BEEN PROMOTED: SKIPPING"
        }
    }

    stage('Create release branch(es) to synchronize with production image(s)') {
        pipelineUtils.echoBanner("CREATE RELEASE DEPLOYMENT BRANCH(ES) FOR PRODUCTION:",
                                 projectInfo.microServices.findAll { it.promote }.collect { it.name }.join(', '))

        if (!projectInfo.hasBeenReleased) {
            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    microService.releaseVersionGitBranch = "v${microService.releaseCandidateGitTag}"
                    dir(microService.workDir) {
                        withCredentials([sshUserPrivateKey(credentialsId: microService.gitSshPrivateKeyName, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                            sh """
                                ${shellEcho "-> Creating deployment branch: ${microService.releaseVersionGitBranch}"}
                                ${sshAgentBash GITHUB_PRIVATE_KEY,
                                               "git branch ${microService.releaseVersionGitBranch}",
                                               "git push origin ${microService.releaseVersionGitBranch}"}
                            """
                        }
                    }
                }
            }
        }
        else {
            echo "-> RELEASE DEPLOYMENT BRANCH(ES) HAVE ALREADY BEEN CREATED: SKIPPING"
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.promote },
                        microServicesToRemove: projectInfo.microServices.findAll { !it.releaseCandidateGitTag },
                        imageTag: projectInfo.releaseVersionTag)

    deployToProductionUtils.updateProjectMetaInfo(projectInfo)

    deployToProductionUtils.cleanupPreviousRelease(projectInfo)
}
