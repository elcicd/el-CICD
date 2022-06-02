/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the refresh-credentials pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/efresh-credentials -pipeline-template.
 *
 */

def call(Map args) {
    onboardingUtils.init()

    dir (el.cicd.PROJECT_DEFS_DIR) {
        def allProjectFiles = []
        allProjectFiles.addAll(findFiles(glob: "**/*.json"))
        allProjectFiles.addAll(findFiles(glob: "**/*.js"))
        allProjectFiles.addAll(findFiles(glob: "**/*.yml"))
        allProjectFiles.addAll(findFiles(glob: "**/*.yaml"))

        def rbacGroups = [:]
        allProjectFiles.each { projectFile ->
            def projectId = projectFile.name.split('[.]')[0]
            def projectInfo = projectUtils.gatherProjectInfoStage(projectId)

            def cicdProjectsExist =
                sh(returnStdout: true, script: "oc get projects --no-headers --ignore-not-found ${projectInfo.cicdMasterNamespace}")
            if (cicdProjectsExist) {
                def ENVS = args.isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]

                if (!rbacGroups[(projectInfo.rbacGroup)]) {
                    rbacGroups[(projectInfo.rbacGroup)] = true

                    stage('Copy el-CICD meta-info pull secrets to rbacGroup Jenkins') {
                        jenkinsUtils.copyElCicdMetaInfoBuildAndPullSecretsToGroupCicdServer(projectInfo, ENVS)
                    }

                    stage('Push el-CICD credentials') {
                        jenkinsUtils.pushElCicdCredentialsToCicdServer(projectInfo, ENVS)
                    }
                }
                else {
                    loggingUtils.echoBanner("${projectInfo.cicdMasterNamespace}'s Automation Server already updated, moving on to updating microservice credentials")
                }

                stage('Delete old github public keys with curl') {
                    githubUtils.deleteProjectDeployKeys(projectInfo)
                }

                def sdlcNamespace = args.isNonProd ? projectInfo.devNamespace : projectInfo.prodNamespace
                def sldcNamespacesExist = sh(returnStdout: true, script: "oc get projects --no-headers --ignore-not-found ${sdlcNamespace}")

                if (sldcNamespacesExist) {
                    stage('Create and push public key for each github repo to github with curl') {
                        onboardingUtils.createAndPushPublicPrivateSshKeys(projectInfo)
                    }

                    stage('Refresh pull secrets per build environment') {
                        loggingUtils.echoBanner("COPY PULL SECRETS TO ALL NAMESPACE ENVIRONMENTS FOR ${projectInfo.id}")

                        if (args.isNonProd) {
                            projectInfo.nonProdNamespaces.each { env, namespace -> 
                                onboardingUtils.copyPullSecretsToEnvNamespace(namespace, env)
                            }

                            projectInfo.sandboxNamespaces.each { env, namespace -> 
                                onboardingUtils.copyPullSecretsToEnvNamespace(namespace, projectInfo.devEnv)
                            }
                        }
                        else {
                            onboardingUtils.copyPullSecretsToEnvNamespace(projectInfo.prodNamespace, projectInfo.prodEnv)
                        }
                    }
                }
                else {
                    loggingUtils.echoBanner("WARNING [${projectInfo.id}]: SDLC namespace ${sdlcNamespace} NOT FOUND; skipping")
                }
            }
            else {
                loggingUtils.echoBanner("WARNING [${projectInfo.id}]: ${projectInfo.cicdMasterNamespace} NOT FOUND; skipping")
            }
        }
    }
}
