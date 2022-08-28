/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the deploy-to-production pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/deploy-to-production-pipeline-template.
 *
 */

def call(Map args) {
    def deployAll = args.deployAll

    def projectInfo = args.projectInfo
    projectInfo.releaseCandidateTag = args.releaseCandidateTag.startsWith(el.cicd.RELEASE_VERSION_PREFIX) ? 
        args.releaseCandidateTag.substring(el.cicd.RELEASE_VERSION_PREFIX.length()) : args.releaseCandidateTag
    projectInfo.releaseVersionTag = "${el.cicd.RELEASE_VERSION_PREFIX}${projectInfo.releaseCandidateTag}"
    projectInfo.deployToEnv = projectInfo.prodEnv
    if (args.releaseRegion) {
        projectInfo.releaseRegion = args.releaseRegion
        projectInfo.deployToRegion = "${projectInfo.deployToEnv}-${projectInfo.releaseRegion}"

        if (!projectInfo.releaseRegions.find { it == projectInfo.releaseRegion }) {
            loggingUtils.errorBanner("REGION ${projectInfo.releaseRegion} IS NOT ONE OF ${projectInfo.releaseRegions}")
        }
    }
    projectInfo.deployToNamespace = projectInfo.prodNamespace


    stage('Gather all git branches, tags, and source commit hashes') {
        loggingUtils.echoBanner("GATHER ALL GIT BRANCHES, TAGS, AND SOURCE COMMIT HASHES")

        deployToProductionUtils.gatherAllVersionGitTagsAndBranches(projectInfo)

        projectInfo.microServicesInRelease = projectInfo.microServices.findAll { it.releaseCandidateGitTag }

        if (!projectInfo.microServicesInRelease)  {
            loggingUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
        else if (projectInfo.microServices.find{ it.deploymentBranch && !it.releaseCandidateGitTag })  {
            loggingUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD SCM STATE FOR RELEASE",
                                      "RELEASE BRANCH AND TAG NAME(S) MUST MATCH, OR HAVE NO RELEASE BRANCHES",
                                      "Release Tags: ${projectInfo.microServices.collect{ it.releaseCandidateGitTag }}",
                                      "Release Branches: ${projectInfo.microServices.collect{ it.deploymentBranch }}")
        }
    }

    stage('Verify images are ready for deployment') {
        loggingUtils.echoBanner("VERIFY PROMOTION AND/OR DEPLOYMENT CAN PROCEED FOR VERSION ${projectInfo.releaseCandidateTag}:",
                                 projectInfo.microServicesInRelease.collect { it.name }.join(', '))

        def allImagesExist = true
        def PROMOTION_ENV_FROM = projectInfo.hasBeenReleased ? projectInfo.PROD_ENV : projectInfo.PRE_PROD_ENV
        withCredentials([string(credentialsId: el.cicd["${PROMOTION_ENV_FROM}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                         variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageTag = projectInfo.hasBeenReleased ? projectInfo.releaseVersionTag : projectInfo.releaseCandidateTag

            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    def copyImageCmd =
                        shCmd.verifyImage(PROMOTION_ENV_FROM, 'IMAGE_REPO_ACCESS_TOKEN', microService.id, imageTag)
                    def imageFound = sh(returnStdout: true, script: "${copyImageCmd}").trim()

                    def msg
                    if (imageFound) {
                        msg = microService.deploymentBranch ?
                            "REDEPLOYMENT CAN PROCEED FOR ${microService.name}" : "PROMOTION DEPLOYMENT CAN PROCEED FOR ${microService.name}"
                    }
                    else {
                        msg = "-> ERROR: no image found in image repo: ${el.cicd["${PROMOTION_ENV_FROM}${el.cicd.IMAGE_REPO_POSTFIX}"]}"
                    }

                    echo msg

                    allImagesExist = allImagesExist && imageFound
                }
            }
        }

        if (!allImagesExist) {
            loggingUtils.errorBanner("BUILD FAILED: Missing image(s) to deploy in PROD")
        }
    }

    stage('Checkout all microservice repositories') {
        loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.microServices.each { microService ->
            def refName = microService.deploymentBranch ?: microService.releaseCandidateGitTag
            if (refName) {
                projectUtils.cloneGitRepo(microService, refName)
            }
        }
    }

    stage('Collect deployment hashes; prune unchanged microservices from deployment') {
        loggingUtils.echoBanner("COLLECT DEPLOYMENT HASHES AND PRUNE UNNECESSARY DEPLOYMENTS")

        def metaInfoReleaseShell =
            "oc get cm ${projectInfo.id}-${el.cicd.CM_META_INFO_POSTFIX} -o jsonpath='{ .data.release-version }' -n ${projectInfo.prodNamespace} || :"
        def metaInfoReleaseChanged = sh(returnStdout: true, script: metaInfoReleaseShell) != projectInfo.releaseVersionTag

        def metaInfoRegionShell =
            "oc get cm ${projectInfo.id}-${el.cicd.CM_META_INFO_POSTFIX} -o jsonpath='{ .data.release-region }' -n ${projectInfo.prodNamespace} || :"
        def metaInfoRegionChanged = sh(returnStdout: true, script: metaInfoRegionShell) != projectInfo.releaseRegion
        projectInfo.microServicesToDeploy = projectInfo.microServicesInRelease.findAll { microService ->
            dir(microService.workDir) {
                def deploymentCommitHashChanged = false
                if (!deployAll && !metaInfoRegionChanged && !metaInfoReleaseChanged) {
                    def depCommitHashScript = "oc get cm ${microService.id}-${el.cicd.CM_META_INFO_POSTFIX} -o jsonpath='{ .data.deployment-commit-hash }' -n ${projectInfo.prodNamespace} || :"
                    deploymentCommitHashChanged = sh(returnStdout: true, script: depCommitHashScript) != microService.deploymentCommitHash
                }

                microService.promote =
                    !projectInfo.hasBeenReleased || metaInfoRegionChanged || deployAll || deploymentCommitHashChanged
            }

            return microService.promote
        }
    }

    stage('Confirm release to production') {
        def promotionOrDeploymentMsg = projectInfo.hasBeenReleased ?
            "CONFIRM REDEPLOYMENT OF ${projectInfo.releaseVersionTag}" :
            "CONFIRM PROMOTION AND DEPLOYMENT OF RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}"

        def deployAllMsg = (projectInfo.hasBeenReleased && deployAll) ?
            '-> YOU HAVE ELECTED TO REDEPLOY ALL MICROSERVICES:' :
            '-> Microservices to be deployed:'

        def msg = loggingUtils.createBanner(
            "${promotionOrDeploymentMsg} TO PRODUCTION",
            "REGION: ${projectInfo.releaseRegion ?: el.cicd.UNDEFINED}",
            '',
            "===========================================",
            '',
            '-> Microservices included in this release:',
            projectInfo.microServicesInRelease.collect { it.name }.join(', '),
            '',
            deployAllMsg,
            projectInfo.microServicesToDeploy.collect { it.name }.join(', '),
            '',
            '-> All other microservices and their associated resources NOT in this release WILL BE REMOVED!',
            projectInfo.microServices.findAll { !it.releaseCandidateGitTag }.collect { it.name }.join(', '),
            '',
            '===========================================',
            '',
            'PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION',
            '',
            'ARE YOU SURE YOU WISH TO PROCEED?'
        )

        input(msg)
    }

    stage('Promote images') {
        loggingUtils.echoBanner("PROMOTE IMAGES TO PROD:",
                                 "${projectInfo.microServicesInRelease.collect { it.name } .join(', ')}")

        if (!projectInfo.hasBeenReleased) {
            withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                             variable: 'PRE_PROD_IMAGE_REGISTRY_ACCESS_TOKEN'),
                             string(credentialsId: el.cicd["${projectInfo.PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                             variable: 'PROD_IMAGE_REGISTRY_ACCESS_TOKEN')])
            {
                projectInfo.microServicesInRelease.each { microService ->
                    def copyImageCmd =
                        shCmd.copyImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_ACCESS_TOKEN', microService.id, projectInfo.releaseCandidateTag,
                                        projectInfo.PROD_ENV, 'PROD_IMAGE_REGISTRY_ACCESS_TOKEN', microService.id, projectInfo.releaseVersionTag)

                    sh """
                        ${shCmd.echo ''}
                        ${copyImageCmd}
                        ${shCmd.echo '',
                                    '******',
                                    "${microService.name}: ${projectInfo.releaseCandidateTag} in ${projectInfo.preProdEnv} PROMOTED TO ${projectInfo.releaseVersionTag} in ${projectInfo.prodEnv}",
                                    '******'}
                    """
                }
            }
        }
        else {
            echo "-> IMAGES HAVE ALREADY BEEN PROMOTED: SKIPPING"
        }
    }

    stage('Create release branch(es) to synchronize with production image(s)') {
        loggingUtils.echoBanner("CREATE RELEASE DEPLOYMENT BRANCH(ES) FOR PRODUCTION:",
                                 projectInfo.microServicesToDeploy.collect { it.name }.join(', '))

        if (!projectInfo.hasBeenReleased) {
            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    microService.deploymentBranch = "v${microService.releaseCandidateGitTag}"
                    dir(microService.workDir) {
                        withCredentials([sshUserPrivateKey(credentialsId: microService.gitRepoDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                            sh """
                                ${shCmd.echo '', "-> Creating deployment branch: ${microService.deploymentBranch}"}
                                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY',
                                                     "git branch ${microService.deploymentBranch}",
                                                     "git push origin ${microService.deploymentBranch}")}
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
                        microServices: projectInfo.microServicesToDeploy,
                        microServicesToRemove: projectInfo.microServices.findAll { !it.releaseCandidateGitTag },
                        imageTag: projectInfo.releaseVersionTag)

    deployToProductionUtils.updateProjectMetaInfo(projectInfo)

    deployToProductionUtils.cleanupPreviousRelease(projectInfo)
}
