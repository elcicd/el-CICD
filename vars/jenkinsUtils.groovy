/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

import groovy.transform.Field

import hudson.AbortException
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

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

def getImageRegistryCredentialsId(def env) {
    return "${env.toLowerCase()}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
}

def copyElCicdCredentialsToCicdServer(def projectInfo, def envs) {
    def keyId = el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")
    withCredentials([sshUserPrivateKey(credentialsId: keyId, keyFileVariable: 'EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID')]) {
        pushSshCredentialsToJenkins(projectInfo, keyId, 'EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID')
    }

    keyId = el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")
    withCredentials([sshUserPrivateKey(credentialsId: keyId, keyFileVariable: 'EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID')]) {
        pushSshCredentialsToJenkins(projectInfo, keyId, 'EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID')
    }

    def credsIds = []
    envs.each { env ->
        def credsId = getImageRegistryCredentialsId(env)
        if (!credsIds.contains(credsId)) {
            loggingUtils.echoBanner("PUSH ${credsId} CREDENTIALS TO CICD SERVER")

            pushImageRegistryCredsToJenkins(projectInfo, credsId)

            credsIds.add(credsId)
        }
    }
}

def pushSshCredentialsToJenkins(def projectInfo, def keyId, def sshKeyGenVar) {
    TEMPLATE_FILE = 'jenkinsSshCredentials-template.xml'
    def JENKINS_CREDS_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"

    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def curlCommand =
            "${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} --data-binary @${JENKINS_CREDS_FILE}"

        sh """
            set +x
            cp ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} ${JENKINS_CREDS_FILE}
            sed -i -e 's/%UNIQUE_ID%/${keyId}/g' ${JENKINS_CREDS_FILE}
            JENKINS_CREDS=\$(<${JENKINS_CREDS_FILE})

            if [[ ! -f ${sshKeyGenVar} ]]
            then
                cp \${${sshKeyGenVar}} ${sshKeyGenVar}
            fi

            echo "\${JENKINS_CREDS//%PRIVATE_KEY%/\$(<${sshKeyGenVar})}" > ${JENKINS_CREDS_FILE}
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

def pushImageRegistryCredsToJenkins(def projectInfo, def credsId) {
    withCredentials([usernamePassword(credentialsId: credsId,
                                      usernameVariable: 'IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'IMAGE_REGISTRY_PASSWORD'),
                     string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def JENKINS_CREDS_FILE = "${el.cicd.TEMPLATES_DIR}/jenkinsUserNamePwdCredentials.xml"
        def curlCommand =
            "${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} --data-binary @${JENKINS_CREDS_FILE}"

        sh """
            ${shCmd.echo ''}
            SED_EXPR="s/%ID%/${credsId}/; s|%USERNAME%|\${IMAGE_REGISTRY_USERNAME}|; s|%PASSWORD%|\${IMAGE_REGISTRY_PASSWORD}|"
            cat ${el.cicd.TEMPLATES_DIR}/jenkinsUsernamePasswordCreds-template.xml | sed "\${SED_EXPR}" > ${JENKINS_CREDS_FILE}

            ${curlCommand} ${projectInfo.jenkinsUrls.CREATE_CREDS}
            ${curlCommand} -f ${projectInfo.jenkinsUrls.UPDATE_CREDS}/${credsId}/config.xml

            rm -f ${JENKINS_CREDS_FILE}
        """
    }
}

def displayInputWithTimeout(def inputMsg, def inputs = null) {
    def cicdInfo
    try {
        timeout(time: el.cicd.JENKINS_INPUT_TIMEOUT) {
            if (inputs) {
                cicdInfo = input(message: inputMsg, parameters: inputs)
            }
            else {
                input(inputMsg)
            }
        }
    }
    catch(AbortException ae) {
        def abortMsg = "${el.cicd.JENKINS_INPUT_TIMEOUT} MINUTE TIMEOUT EXCEEDED WAITING FOR USER INPUT"
        loggingUtils.errorBanner(abortMsg, '', 'EXITING PIPELINE...')
    }
    catch(FlowInterruptedException fie) {
        loggingUtils.errorBanner('USER ABORTED PIPELINE RUN.  EXITING PIPELINE...')
    }
    catch(err) {
        throw err
    }

    return cicdInfo
}