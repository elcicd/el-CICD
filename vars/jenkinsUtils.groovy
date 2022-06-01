/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

import groovy.transform.Field

@Field
def CREATE_CREDS_PATH = 'credentials/store/system/domain/_/CREATE_CREDS_PATH'

@Field
def SYSTEM_DOMAIN_CREDS_PATH = 'system/domain/_/credential'

@Field
def CREATE_ITEM = 'createItem'

@Field
def JOB = 'job'

@Field
def NAME = 'name'

@Field
def CONFIG_ITEM = 'config.xml'

@Field
def FOLDER_ITEM = 'folder.xml'

@Field
def API_JSON = 'api/json'

def configureTeamJenkinsUrls(def projectInfo) {        
    projectInfo.jenkinsUrls = [:]
    projectInfo.jenkinsUrls.HOST = "https://jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    projectInfo.jenkinsUrls.CREATE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${CREATE_CREDS_PATH}"
    projectInfo.jenkinsUrls.UDPATE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${SYSTEM_DOMAIN_CREDS_PATH}/"
    projectInfo.jenkinsUrls.DELETE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${SYSTEM_DOMAIN_CREDS_PATH}/doDelete"
    
    projectInfo.jenkinsUrls.ACCESS_FOLDER = "${projectInfo.jenkinsUrls.HOST}/${JOB}"
 }

def getJenkinsCurlCommand(def httpVerb) {
    return getJenkinsCurlCommand(httpVerb, '')
}

def getJenkinsCurlCommand(def httpVerb, def headerType, def output = '-o /dev/null') {
    def header = ''
    switch (headerType) {
        case 'XML':
            header = "-H 'Content-Type:text/xml'"
    }
    
    return """ curl -ksS ${output ?: ''} -X ${httpVerb} ${header} -H "Authorization: Bearer \${JENKINS_ACCESS_TOKEN}" """
}

def createPipelinesFolder(def projectInfo, def folderName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${getJenkinsCurlCommand('POST', 'XML')} ${projectInfo.jenkinsUrls.HOST}/${CREATE_ITEM}?${NAME}=${folderName} \
                --data-binary @${el.cicd.EL_CICD_PIPELINES_DIR}/${FOLDER_ITEM}
        """
    }
}
 
def deletePipelinesFolder(def projectInfo, def folderName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh "${getJenkinsCurlCommand('DELETE')} ${projectInfo.jenkinsUrls.HOST}/${folderName}/"
    }
}

def listPipelinesInFolder(def projectInfo, def folderName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def listOfPipelines =
            sh(returnStdout: true, script: """
                ${getJenkinsCurlCommand('GET', null, null)} -f ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}/${API_JSON} | jq -r '.jobs[].name'
            """).split(/\s/)
    }
}

def createPipeline(def projectInfo, def folderName, def pipelineFileDir, def pipelineFile) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            PIPELINE_FILE=${pipelineFile.name}
            ${shCmd.echo 'Creating ${PIPELINE_FILE%.*} pipeline'}
            ${getJenkinsCurlCommand('POST', 'XML')} \
                ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}/${CREATE_ITEM}?${NAME}=\${PIPELINE_FILE%.*} \
                --data-binary @${pipelineFileDir}/${pipelineFile.name}
        """
    }
}

def deletePipeline(def projectInfo, def folderName, def pipelineName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${shCmd.echo "Removing pipeline: ${pipelineName}"}
            ${getJenkinsCurlCommand('DELETE')} ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}//${JOB}/${pipelineName}/
        """
    }
}

def copyElCicdMetaInfoBuildAndPullSecretsToGroupCicdServer(def projectInfo, def ENVS) {
    loggingUtils.echoBanner("COPY el-CICD META-INFO AND ALL PULL SECRETS TO NAMESPACE ENVIRONMENTS FOR ${projectInfo.cicdMasterNamespace}")

    def pullSecretNames = ENVS.collect { el.cicd["${it}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"] }.toSet()
    def copyBuildSecrets = ENVS.contains(projectInfo.DEV_ENV)

    sh """
        ${shCmd.echo ''}
        oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${el.cicd.ONBOARDING_MASTER_NAMESPACE} | \
            ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
            oc apply -f - -n ${projectInfo.cicdMasterNamespace}

        BUILD_SECRETS_NAME=${(copyBuildSecrets && el.cicd.EL_CICD_BUILD_SECRETS_NAME) ? el.cicd.EL_CICD_BUILD_SECRETS_NAME : ''}
        if [[ ! -z \${BUILD_SECRETS_NAME} ]]
        then
            ${shCmd.echo ''}
            oc get secret \${BUILD_SECRETS_NAME} -o yaml -n ${el.cicd.ONBOARDING_MASTER_NAMESPACE} | \
                ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                oc apply -f - -n ${projectInfo.cicdMasterNamespace}
        fi

        for PULL_SECRET_NAME in ${pullSecretNames.join(' ')}
        do
            ${shCmd.echo ''}
            oc get secrets \${PULL_SECRET_NAME} -o yaml -n ${el.cicd.ONBOARDING_MASTER_NAMESPACE} | \
                ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                oc apply -f - -n ${projectInfo.cicdMasterNamespace}
        done
    """
}

def pushElCicdCredentialsToCicdServer(def projectInfo, def ENVS) {
    def keyId = el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")

    def jenkinsUrls = getJenkinsCredsUrls(projectInfo, keyId)
    try {
        pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, keyId)
    }
    catch (Exception e) {
        echo "Creating ${keyId} on CICD failed, trying update"
    }
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, keyId)

    keyId = el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")
    jenkinsUrls = getJenkinsCredsUrls(projectInfo, keyId)
    try {
        pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, keyId)
    }
    catch (Exception e) {
        echo "Creating ${keyId} on CICD failed, trying update"
    }
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, keyId)

    def tokenIds = []
    ENVS.each { ENV ->
        def tokenId = el.cicd["${ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"]
        if (!tokenIds.contains(tokenId)) {
            loggingUtils.shellEchoBanner("PUSH ${tokenId} CREDENTIALS TO CICD SERVER")

            jenkinsUrls = getJenkinsCredsUrls(projectInfo, tokenId)
            try {
                pushImageRepositoryTokenToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, tokenId)
            }
            catch (Exception e) {
                echo "Creating ${tokenId} on CICD failed, trying update"
            }
            pushImageRepositoryTokenToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, tokenId)

            tokenIds.add(tokenId)
        }
    }
}

def deleteDeployKeysFromJenkins(def projectInfo) {
    def jenkinsUrl =
        "https://jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/credentials/store/system/domain/_/credential/"

    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        projectInfo.components.each { component ->
            sh """
                ${shCmd.echo ''}
                ${getJenkinsCurlCommand('POST')} ${jenkinsUrl}/${component.gitSshPrivateKeyName}/doDelete
            """
        }
    }
}

def pushSshCredentialsToJenkins(def cicdJenkinsNamespace, def url, def keyId) {
    def SECRET_FILE_NAME = "${el.cicd.TEMP_DIR}/elcicdReadOnlyGithubJenkinsSshCredentials.xml"
    def credsArray = [sshUserPrivateKey(credentialsId: "${keyId}", keyFileVariable: "KEY_ID_FILE"),
                      string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]
    def curlCommand = """${getJenkinsCurlCommand('POST')} -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${url}"""
    withCredentials(credsArray) {
        def httpCode = 
            sh(returnStdout: true, script: """
                ${shCmd.echo ''}
                cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed 's/%UNIQUE_ID%/${keyId}/g' > ${SECRET_FILE_NAME}
                cat \${KEY_ID_FILE} >> ${SECRET_FILE_NAME}
                cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE_NAME}

                ${curlCommand}

                rm -f ${SECRET_FILE_NAME}
            """).replaceAll(/[\s]/, '')
        if (!httpCode.startsWith('2')) {
            loggingUtils.errorBanner("Push SSH private key (${keyId}) to Jenkins failed with HTTP code: ${httpCode}")
        }
    }
}

def pushImageRepositoryTokenToJenkins(def cicdJenkinsNamespace, def url, def tokenId) {
    def credsArray = [string(credentialsId: tokenId, variable: 'IMAGE_REPO_ACCESS_TOKEN',
                      string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN'))]
                      
    withCredentials(credsArray) {
        def curlCommand = """${getJenkinsCurlCommand('POST')} -H "content-type:application/xml" --data-binary @jenkinsTokenCredentials.xml ${url}"""
        def httpCode = 
            sh(returnStdout: true, script: """
                ${shCmd.echo ''}
                cat ${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${tokenId}/g" > jenkinsTokenCredentials-named.xml
                cat jenkinsTokenCredentials-named.xml | sed "s|%TOKEN%|\${IMAGE_REPO_ACCESS_TOKEN}|g" > jenkinsTokenCredentials.xml

                ${curlCommand}

                rm -f jenkinsTokenCredentials-named.xml jenkinsTokenCredentials.xml
            """).replaceAll(/[\s]/, '')
        if (!httpCode.startsWith('2')) {
            loggingUtils.errorBanner("Push image repo access token (${tokenId}) to Jenkins failed with HTTP code: ${httpCode}")
        }
    }
}

def pushPrivateSshKey() {
    def jenkinsUrls = getJenkinsCredsUrls(projectInfo, component.gitSshPrivateKeyName)
    def jenkinsCurlCommand =
        """${getJenkinsCurlCommand('POST')} -H "content-type:application/xml" --data-binary @${credsFileName}"""
        
    sh """
        ${shCmd.echo  '', "ADDING PRIVATE KEY FOR GIT REPO ON CICD JENKINS: ${component.name}"}
        cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${component.gitSshPrivateKeyName}/g" > ${credsFileName}
        cat ${component.gitSshPrivateKeyName} >> ${credsFileName}
        cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${credsFileName}

        ${jenkinsCurlCommand} ${jenkinsUrls.createCredsUrl}
        ${jenkinsCurlCommand} ${jenkinsUrls.updateCredsUrl}

        rm -f ${credsFileName} ${component.gitSshPrivateKeyName} ${component.gitSshPrivateKeyName}.pub
    """
}