/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines creates and pushes new deploy keys to Jenkins and GitHub
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def isNonProd = args.isNonProd

    jenkinsUtils.configureCicdJenkinsUrls(projectInfo)

    def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
    stage('Push Image Repo Pull Secrets to CICD Jenkins') {
        jenkinsUtils.copyElCicdCredentialsToCicdServer(projectInfo, envs)
    }

    loggingUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM PROJECT ${projectInfo.id} GIT REPOS")
    def buildStages =  concurrentUtils.createParallelStages('Delete old SCM deploy keys', projectInfo.modules) { module ->
        githubUtils.deleteProjectDeployKeys(module)
    }    
    parallel(buildStages)

    stage('Create and push public key for each github repo to github with curl') {
        loggingUtils.echoBanner("CREATE DEPLOY KEYS FOR EACH GIT REPO:",
                                " - PUSH EACH PRIVATE KEY TO THE el-CICD ${projectInfo.rbacGroups[projectInfo.devEnv]} CICD JENKINS",
                                " - PUSH EACH PUBLIC KEY FOR EACH PROJECT REPO TO THE SCM HOST")

        projectInfo.modules.each { module ->
            dir(module.workDir) {
                echo "Pushing deploy key for ${module.scmRepoName}"

                sh """
                    ssh-keygen -b 2048 -t rsa -f '${module.scmDeployKeyJenkinsId}' \
                        -q -N '' -C 'el-CICD Component Deploy key' 2>/dev/null <<< y >/dev/null
                """

                jenkinsUtils.pushSshCredentialsToJenkins(projectInfo, module.scmDeployKeyJenkinsId, module.scmDeployKeyJenkinsId)

                githubUtils.addProjectDeployKey(module, "${module.scmDeployKeyJenkinsId}.pub")

                sh "rm -f ${module.scmDeployKeyJenkinsId} ${module.scmDeployKeyJenkinsId}.pub"
            }
        }
    }
}