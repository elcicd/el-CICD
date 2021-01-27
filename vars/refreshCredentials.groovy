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
            def projectInfo = pipelineUtils.gatherProjectInfoStage(projectId)

            def cicdProjectsExist = sh(returnStdout: true, script: "oc get projects --ignore-not-found ${projectInfo.cicdMasterNamespace}")
            if (cicdProjectsExist) {
                def envs = args.isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]

                if (!rbacGroups[(projectInfo.rbacGroup)]) {
                    rbacGroups[(projectInfo.rbacGroup)] = true

                    stage('Copy el-CICD meta-info pull secrets to rbacGroup Jenkins') {
                        credentialUtils.copyElCicdMetaInfoAndPullSecretsToGroupCicdServer(projectInfo, envs)
                    }

                    stage('Push el-CICD credentials') {
                        credentialUtils.pushElCicdCredentialsToCicdServer(projectInfo, envs)
                    }
                }
                else {
                    pipelineUtils.echoBanner("${projectInfo.cicdMasterNamespace}'s Automation Server already updated, moving on to updating microservice credentials")
                }

                stage('Delete old github public keys with curl') {
                    credentialUtils.deleteDeployKeysFromGithub(projectInfo)
                }

                stage('Create and push public key for each github repo to github with curl') {
                    credentialUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
                }

                stage('Refresh pull secrets per build environment') {
                    credentialUtils.copySecretsFromElCicdMasterToGroupCicdServer(projectInfo,
                                                                                 projectInfo.nonProdNamespaces.values(),
                                                                                 projectInfo.nonProdNamespaces.keySet())
                }
            }
            else {
                pipelineUtils.echoBanner("WARNING [${projectInfo.id}]: ${projectInfo.cicdMasterNamespace} NOT FOUND; skipping")
            }
        }
    }
}
