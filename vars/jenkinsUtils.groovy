/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

import groovy.transform.Field

@Field
def JENKINS_CREDS_BASE_PATH = 'credentials/store/system/domain/_'

@Field
def CREATE_CREDS_PATH = "${JENKINS_CREDS_BASE_PATH}/createCredentials"

@Field
def JENKINS_CREDS_PATH = "${JENKINS_CREDS_BASE_PATH}/credential"

@Field
def XML_CONTEXT_HEADER = "-H 'Content-Type:text/xml'"

@Field
def CURL_FAIL_FLAG = "-H 'Content-Type:text/xml'"

@Field
def CURL_NO_OUTPUT = '-o /dev/null'

def configureCicdJenkinsUrls(def projectInfo) {        
    projectInfo.jenkinsUrls = [:]
    projectInfo.jenkinsUrls.HOST = "https://jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    projectInfo.jenkinsUrls.CREATE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${CREATE_CREDS_PATH}"
    projectInfo.jenkinsUrls.UPDATE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${JENKINS_CREDS_PATH}"
    projectInfo.jenkinsUrls.DELETE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${JENKINS_CREDS_PATH}/doDelete"
}

def getImageRegistryPullTokenId(def env) {
    return "${env.toLowerCase()}${el.clcd.IMAGE_REGISTRY_PULL_TOKEN_ID_POSTFIX}"}
}

def copyElCicdCredentialsToCicdServer(def projectInfo, def envs) {
    def keyId = el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")
    withCredentials([sshUserPrivateKey(credentialsId: keyId, keyFileVariable: 'EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID')]) {
        pushSshCredentialsToJenkins(projectInfo, keyId, EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID)
    }

    keyId = el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")
    withCredentials([sshUserPrivateKey(credentialsId: keyId, keyFileVariable: 'EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID')]) {
        pushSshCredentialsToJenkins(projectInfo, keyId, EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID)
    }

    def tokenIds = []
    envs.each { env ->
        def tokenId = getImageRegistryPullTokenId(env)
        if (!tokenIds.contains(tokenId)) {
            loggingUtils.shellEchoBanner("PUSH ${tokenId} CREDENTIALS TO CICD SERVER")

            pushImageRepositoryTokenToJenkins(projectInfo, tokenId)

            tokenIds.add(tokenId)
        }
    }
}

def pushSshCredentialsToJenkins(def projectInfo, def keyId, def sshKeyVar) {
    TEMPLATE_FILE = 'jenkinsSshCredentials-template.xml'
    def JENKINS_CREDS_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"
    
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def curlCommand =
            "${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} --data-binary @${JENKINS_CREDS_FILE}"
        
        sh """
            ${shCmd.echo ''}
            set +x
            cp ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} ${JENKINS_CREDS_FILE}
            sed -i -e 's/%UNIQUE_ID%/${keyId}/g' ${JENKINS_CREDS_FILE}
            JENKINS_CREDS=\$(<${JENKINS_CREDS_FILE})
            echo "\${JENKINS_CREDS//%PRIVATE_KEY%/\$(<${sshKeyVar})}" > ${JENKINS_CREDS_FILE}
            set -x
            
            ${curlCommand} ${projectInfo.jenkinsUrls.CREATE_CREDS}
            ${curlCommand} -f ${projectInfo.jenkinsUrls.UPDATE_CREDS}/${keyId}/config.xml

            rm -f ${JENKINS_CREDS_FILE}
        """
    }
}

def deleteProjectDeployKeyFromJenkins(def projectInfo, def module) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${shCmd.echo ''}
            ${curlUtils.getCmd(curlUtils.POST)} ${projectInfo.jenkinsUrls.DELETE_CREDS}/${module.gitDeployKeyJenkinsId}/doDelete
        """
    }
}

def pushImageRepositoryTokenToJenkins(def projectInfo, def tokenId) {
    withCredentials([string(credentialsId: tokenId, variable: 'IMAGE_REGISTRY_PULL_TOKEN'),
                     string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def JENKINS_CREDS_FILE = "${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials.xml"
        def curlCommand =
            "${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} --data-binary @${JENKINS_CREDS_FILE}"
        
        sh """
            ${shCmd.echo ''}
            cat ${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | \
                sed "s/%ID%/${tokenId}/; s|%TOKEN%|\${IMAGE_REGISTRY_PULL_TOKEN}|" > ${JENKINS_CREDS_FILE}

            ${curlCommand} ${projectInfo.jenkinsUrls.CREATE_CREDS}
            ${curlCommand} -f ${projectInfo.jenkinsUrls.UPDATE_CREDS}/${tokenId}/config.xml

            rm -f ${JENKINS_CREDS_FILE}
        """
    }
}