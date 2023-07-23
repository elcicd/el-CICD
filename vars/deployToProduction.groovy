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
    projectInfo.versionTag = args.versionTag

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

        projectInfo.componentsInRelease = projectInfo.components.findAll { it.releaseCandidateGitTag }

        if (!projectInfo.componentsInRelease)  {
            loggingUtils.errorBanner("${projectInfo.versionTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
        else if (projectInfo.components.find{ it.deploymentBranch && !it.releaseCandidateGitTag })  {
            loggingUtils.errorBanner("${projectInfo.versionTag}: BAD SCM STATE FOR RELEASE",
                                      "RELEASE BRANCH AND TAG NAME(S) MUST MATCH, OR HAVE NO RELEASE BRANCHES",
                                      "Release Tags: ${projectInfo.components.collect{ it.releaseCandidateGitTag }}",
                                      "Release Branches: ${projectInfo.components.collect{ it.deploymentBranch }}")
        }
    }

    stage('Verify images are ready for deployment') {
        loggingUtils.echoBanner("VERIFY PROMOTION AND/OR DEPLOYMENT CAN PROCEED FOR VERSION ${projectInfo.versionTag}:",
                                 projectInfo.componentsInRelease.collect { it.name }.join(', '))

        def allImagesExist = true
        def PROMOTION_ENV_FROM = projectInfo.hasBeenReleased ? projectInfo.PROD_ENV : projectInfo.PRE_PROD_ENV
        withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(PROMOTION_ENV_FROM),
                         variable: 'IMAGE_REGISTRY_PULL_TOKEN')]) {
            def imageTag = projectInfo.hasBeenReleased ? projectInfo.versionTag : projectInfo.versionTag

            projectInfo.components.each { component ->
                if (component.releaseCandidateGitTag) {
                    def copyImageCmd =
                        shCmd.verifyImage(PROMOTION_ENV_FROM, 'IMAGE_REGISTRY_PULL_TOKEN', component.id, imageTag)
                    def imageFound = sh(returnStdout: true, script: "${copyImageCmd}").trim()

                    def msg
                    if (imageFound) {
                        msg = component.deploymentBranch ?
                            "REDEPLOYMENT CAN PROCEED FOR ${component.name}" : "PROMOTION DEPLOYMENT CAN PROCEED FOR ${component.name}"
                    }
                    else {
                        msg = "--> ERROR: no image found in image repo: ${el.cicd["${PROMOTION_ENV_FROM}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]}"
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

    stage('Checkout all component repositories') {
        loggingUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.components.each { component ->
            def refName = component.deploymentBranch ?: component.releaseCandidateGitTag
            if (refName) {
                projectInfoUtils.cloneGitRepo(component, refName)
            }
        }
    }

    stage('Collect deployment hashes; prune unchanged components from deployment') {
        loggingUtils.echoBanner("COLLECT DEPLOYMENT HASHES AND PRUNE UNNECESSARY DEPLOYMENTS")

        def metaInfoReleaseShell =
            "oc get cm ${projectInfo.id}-${el.cicd.META_INFO_POSTFIX} -o jsonpath='{ .data.release-version }' -n ${projectInfo.prodNamespace} || :"
        def metaInfoReleaseChanged = sh(returnStdout: true, script: metaInfoReleaseShell) != projectInfo.versionTag

        def metaInfoRegionShell =
            "oc get cm ${projectInfo.id}-${el.cicd.META_INFO_POSTFIX} -o jsonpath='{ .data.release-region }' -n ${projectInfo.prodNamespace} || :"
        def metaInfoRegionChanged = sh(returnStdout: true, script: metaInfoRegionShell) != projectInfo.releaseRegion
        projectInfo.componentsToDeploy = projectInfo.componentsInRelease.findAll { component ->
            dir(component.workDir) {
                def deploymentCommitHashChanged = false
                if (!deployAll && !metaInfoRegionChanged && !metaInfoReleaseChanged) {
                    def depCommitHashScript = "oc get cm ${component.id}-${el.cicd.META_INFO_POSTFIX} -o jsonpath='{ .data.deployment-commit-hash }' -n ${projectInfo.prodNamespace} || :"
                    deploymentCommitHashChanged = sh(returnStdout: true, script: depCommitHashScript) != component.deploymentCommitHash
                }

                component.promote =
                    !projectInfo.hasBeenReleased || metaInfoRegionChanged || deployAll || deploymentCommitHashChanged
            }

            return component.promote
        }
    }

    stage('Confirm release to production') {
        def promotionOrDeploymentMsg = projectInfo.hasBeenReleased ?
            "CONFIRM REDEPLOYMENT OF ${projectInfo.versionTag}" :
            "CONFIRM PROMOTION AND DEPLOYMENT OF RELEASE CANDIDATE ${projectInfo.versionTag}"

        def deployAllMsg = (projectInfo.hasBeenReleased && deployAll) ?
            '-> YOU HAVE ELECTED TO REDEPLOY ALL COMPONENTS:' :
            '-> Microservices to be deployed:'

        def msg = loggingUtils.createBanner(
            "${promotionOrDeploymentMsg} TO PRODUCTION",
            "REGION: ${projectInfo.releaseRegion ?: el.cicd.UNDEFINED}",
            '',
            "===========================================",
            '',
            '-> Microservices included in this release:',
            projectInfo.componentsInRelease.collect { it.name }.join(', '),
            '',
            deployAllMsg,
            projectInfo.componentsToDeploy.collect { it.name }.join(', '),
            '',
            '-> All other components and their associated resources NOT in this release WILL BE REMOVED!',
            projectInfo.components.findAll { !it.releaseCandidateGitTag }.collect { it.name }.join(', '),
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
                                 "${projectInfo.componentsInRelease.collect { it.name } .join(', ')}")

        if (!projectInfo.hasBeenReleased) {
            withCredentials([string(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                             variable: 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN'),
                             string(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.prodEnv),
                             variable: 'PROD_IMAGE_REGISTRY_PULL_TOKEN')])
            {
                projectInfo.componentsInRelease.each { component ->
                    def copyImageCmd =
                        shCmd.copyImage(projectInfo.PRE_PROD_ENV, 'PRE_PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.versionTag,
                                        projectInfo.PROD_ENV, 'PROD_IMAGE_REGISTRY_PULL_TOKEN', component.id, projectInfo.versionTag)

                    sh """
                        ${shCmd.echo ''}
                        ${copyImageCmd}
                        ${shCmd.echo '',
                                    '******',
                                    "${component.name}: ${projectInfo.versionTag} in ${projectInfo.preProdEnv} PROMOTED TO ${projectInfo.versionTag} in ${projectInfo.prodEnv}",
                                    '******'}
                    """
                }
            }
        }
        else {
            echo "--> IMAGES HAVE ALREADY BEEN PROMOTED: SKIPPING"
        }
    }

    stage('Create release branch(es) to synchronize with production image(s)') {
        loggingUtils.echoBanner("CREATE RELEASE DEPLOYMENT BRANCH(ES) FOR PRODUCTION:",
                                 projectInfo.componentsToDeploy.collect { it.name }.join(', '))

        if (!projectInfo.hasBeenReleased) {
            projectInfo.components.each { component ->
                if (component.releaseCandidateGitTag) {
                    component.deploymentBranch = "v${component.releaseCandidateGitTag}"
                    dir(component.workDir) {
                        withCredentials([sshUserPrivateKey(credentialsId: component.repoDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                            sh """
                                ${shCmd.echo '', "--> Creating deployment branch: ${component.deploymentBranch}"}
                                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY',
                                                     "git branch ${component.deploymentBranch}",
                                                     "git push origin ${component.deploymentBranch}")}
                            """
                        }
                    }
                }
            }
        }
        else {
            echo "--> RELEASE DEPLOYMENT BRANCH(ES) HAVE ALREADY BEEN CREATED: SKIPPING"
        }
    }

    deployComponents(projectInfo: projectInfo,
                     componentsToDeploy: projectInfo.componentsToDeploy,
                     componentsToRemove: projectInfo.components.findAll { !it.releaseCandidateGitTag },
                     imageTag: projectInfo.versionTag)

    deployToProductionUtils.updateProjectMetaInfo(projectInfo)

    deployToProductionUtils.cleanupPreviousRelease(projectInfo)
}
