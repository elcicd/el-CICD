/*
 * Defines the bulk of the deploy-to-production pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/deploy-to-production-pipeline-template.
 *
 */

def call(Map args) {

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo()

    projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
    projectInfo.releaseCandidateTag = args.releaseCandidateTag
    projectInfo.deployToEnv = projectInfo.preProdEnv
    projectInfo.deployToNamespace = projectInfo.preProdNamespace

    stage('Gather all git branches, tags, and source commit hashes') {
        pipelineUtils.echoBanner("GATHER ALL GIT BRANCHES, TAGS, AND SOURCE COMMIT HASHES")

        deployToProductionUtils.gatherAllVersionGitTagsAndBranches(projectInfo)

        if (!projectInfo.microServices.find{ it.releaseCandidateGitTag })  {
            pipelineUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
    }

    stage('Verify release candidate images exist for redeployment') {
        pipelineUtils.echoBanner("VERIFY REDEPLOYMENT CAN PROCEED FOR RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}:",
                                 projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(', '))

        def allImagesExist = true
        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_USERNAME"] + ":${IMAGE_REPO_ACCESS_TOKEN}"
            def imageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO"]

            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    def imageUrl = "docker://${imageRepo}/${microService.id}:${projectInfo.PRE_PROD_ENV}"

                    def imageFound = sh(returnStdout: true, script: "skopeo inspect --raw --creds ${imageRepoUserNamePwd} ${imageUrl} 2>&1 || :").trim()

                    def msg = imageFound ? "REDEPLOYMENT CAN PROCEED FOR ${microService.name}" : "-> ERROR: no image found in image repo: ${projectInfo.PRE_PROD_ENV}"
                    echo msg

                    allImagesExist = allImagesExist && imageFound
                }
            }
        }

        if (!allImagesExist) {
            pipelineUtils.errorBanner("BUILD FAILED: Missing image(s) to deploy in ${projectInfo.PRE_PROD_ENV} for release candidate ${projectInfo.releaseCandidateTag}")
        }
    }

    stage('Checkout all release candidate microservice repositories') {
        pipelineUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.microServices.each { microService ->
            if (microService.releaseCandidateGitTag) {
                def srcCommitHash = microService.releaseCandidateGitTag.split('-')[1]
                def refName = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.preProdEnv}-${srcCommitHash}"
                pipelineUtils.cloneGitRepo(microService, refName)
            }
        }
    }

    stage('Confirm release candidate deployment') {
        input("""

            ===========================================
            CONFIRM REDEPLOYMENT OF ${projectInfo.releaseCandidateTag} to ${projectInfo.preProdEnv}
            ===========================================

            *******
            -> Microservices included in this release candidate to be deployed:
            ${projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(', ')}
            *******

            *******
            -> ${projectInfo.deployToNamespace} will be cleaned of all other project resources before deployment
            *******

            ===========================================
            PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION

            ARE YOU SURE YOU WISH TO PROCEED?
            ===========================================
        """)
    }

    stage('Tag images') {
        pipelineUtils.echoBanner("TAG IMAGES TO ${projectInfo.PRE_PROD_ENV}:", "${projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name } .join(', ')}")

        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN')]) {
            def userNamePwd = el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_USERNAME"] + ":${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN}"
            def skopeoCopyComd = "skopeo copy --src-creds ${userNamePwd} --dest-creds ${userNamePwd} --src-tls-verify=false --dest-tls-verify=false"

            def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO"]

            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    def preProdImageUrl = "${preProdImageRepo}/${microService.id}"

                    def msg =
                        "${microService.name}: ${projectInfo.releaseCandidateTag} TAGGED AS ${projectInfo.preProdEnv} and ${projectInfo.preProdEnv}-${microService.srcCommitHash}"

                    sh """
                        ${skopeoCopyComd} docker://${preProdImageUrl}:${projectInfo.releaseCandidateTag} docker://${preProdImageUrl}:${projectInfo.preProdEnv}-${microService.srcCommitHash}

                        ${skopeoCopyComd} docker://${preProdImageUrl}:${projectInfo.releaseCandidateTag} docker://${preProdImageUrl}:${projectInfo.preProdEnv}

                        ${shellEcho "******",
                                    msg,
                                    "******"}
                    """
                }
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.releaseCandidateGitTag },
                        imageTag: projectInfo.preProdEnv,
                        recreateAll: true)
}
