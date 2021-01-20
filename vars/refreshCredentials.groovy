/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the refresh-credentials pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/efresh-credentials -pipeline-template.
 *
 */

def call(Map args) {
    dir (el.cicd.PROJECT_DEFS_DIR) {
        def allProjectFiles = []
        allProjectFiles.addAll(findFiles(glob: "**/*.json"))
        allProjectFiles.addAll(findFiles(glob: "**/*.js"))
        allProjectFiles.addAll(findFiles(glob: "**/*.yml"))
        allProjectFiles.addAll(findFiles(glob: "**/*.yaml"))

        echo "allProjectFiles: ${allProjectFiles}"

        allProjectFiles.each { projectFile ->
            stage('testing') {
                echo "projectFile ${projectFile.name}"
                def projectId = projectFile.name.replaceAll('[.](yml||yaml|json|js)', '')​
                echo 'howdy1'
                def projectInfo = pipelineUtils.gatherProjectInfoStage(projectId)
                echo 'howdy2'
                def envs = args.isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
            }

            stage('Push el-CICD credentials') {
                credentialsUtils.pushElCicdCredentialsToCicdServer(projectInfo, envs)
            }

            stage('Delete old github public keys with curl') {
                credentialsUtils.deleteDeployKeysFromGithub(projectInfo)
            }

            stage('Create and push public key for each github repo to github with curl') {
                credentialsUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
            }
        }
    }
}
