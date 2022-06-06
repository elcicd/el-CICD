/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines creates and pushes new deploy keys to Jenkins and GitHub
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def isNonProd = args.isNonProd

    def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
    jenkinsUtils.copyElCicdMetaInfoBuildAndPullSecretsToGroupCicdServer(projectInfo, envs)

    stage('Push Image Repo Pull Secrets to rbacGroup Jenkins') {
        jenkinsUtils.copyElCicdCredentialsToCicdServer(projectInfo, envs)
    }

    stage('Delete old github public keys') {
        loggingUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM PROJECT GIT REPOS")
        
        projectInfo.components.each { component ->
            githubUtils.deleteProjectDeployKeys(projectInfo, component)
        }
    }

    stage('Create and push public key for each github repo to github with curl') {
        loggingUtils.echoBanner("CREATE DEPLOY KEYS FOR EACH GIT REPO:",
                                " - PUSH EACH PRIVATE KEY TO THE el-CICD ${projectInfo.rbacGroup} CICD JENKINS",
                                " - PUSH EACH PUBLIC KEY FOR EACH PROJECT REPO TO THE SCM HOST")
                                
        projectInfo.components.each { component ->
            dir(component.workDir) {
                echo "Pushing deploy key for ${component.gitRepoName}"
                
                sh """
                    ssh-keygen -b 2048 -t rsa -f '${component.gitDeployKeyJenkinsId}' \
                        -q -N '' -C 'el-CICD Component Deploy key' 2>/dev/null <<< y >/dev/null
                """
                
                def envVar = component.gitDeployKeyJenkinsId.replaceAll(/\p{Punct}/, '_')
                def sshKey = readFile component.gitDeployKeyJenkinsId
                withEnv(["${envVar}=${sshKey}"]) {
                    jenkinsUtils.pushSshCredentialsToJenkins(projectInfo, component.gitDeployKeyJenkinsId, envVar)
                }
                
                githubUtils.addProjectDeployKey(projectInfo, component, "${component.gitDeployKeyJenkinsId}.pub")
            }
        }
    }
}