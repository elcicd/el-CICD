/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

import groovy.transform.Field

@Field
def GET = 'GET'

@Field
def POST = 'POST'

@Field
def DELETE = 'DELETE'

@Field
def CREATE_CREDS_PATH = 'credentials/store/system/domain/_/createCredentials'

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
    projectInfo.jenkinsUrls.UPDATE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${SYSTEM_DOMAIN_CREDS_PATH}/"
    projectInfo.jenkinsUrls.DELETE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${SYSTEM_DOMAIN_CREDS_PATH}/doDelete"
    
    projectInfo.jenkinsUrls.ACCESS_FOLDER = "${projectInfo.jenkinsUrls.HOST}/${JOB}"
}

def createPipelinesFolder(def projectInfo, def folderName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN', )} ${curlUtils.XML_CONTEXT_HEADER} \
                ${projectInfo.jenkinsUrls.HOST}/${CREATE_ITEM}?${NAME}=${folderName} \
                --data-binary @${el.cicd.EL_CICD_PIPELINES_DIR}/${FOLDER_ITEM}
        """
    }
}
 
def deletePipelinesFolder(def projectInfo, def folderName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${curlUtils.getCmd(curlUtils.DELETE, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} \
                ${projectInfo.jenkinsUrls.HOST}/${folderName}/
        """
    }
}

def listPipelinesInFolder(def projectInfo, def folderName) {
    def listOfPipelines = []
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {        
        def curlScript =  """
            ${curlUtils.getCmd(curlUtils.GET, 'JENKINS_ACCESS_TOKEN', false)} ${curlUtils.FAIL_SILENT} \
                ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}/${API_JSON} |  \
                jq -r '.jobs[].name'
        """
        
        listOfPipelinesArray =  sh(returnStdout: true, script: curlScript).split(/\s+/)
        listOfPipelines.addAll(listOfPipelinesArray)
    }
    return listOfPipelines
}

def createPipeline(def projectInfo, def folderName, def pipelineFileDir, def pipelineFile) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            PIPELINE_FILE=${pipelineFile.name}
            ${shCmd.echo 'Creating ${PIPELINE_FILE%.*} pipeline'}
            
            ${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} \
                ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}/${CREATE_ITEM}?${NAME}=\${PIPELINE_FILE%.*} \
                --data-binary @${pipelineFileDir}/${pipelineFile.name}
        """
    }
}

def deletePipeline(def projectInfo, def folderName, def pipelineName) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${shCmd.echo "Removing pipeline: ${pipelineName}"}
            ${curlUtils.getCmd(curlUtils.DELETE)} ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}/${JOB}/${pipelineName}/
        """
    }
}

def pushSshCredentialsToJenkins(def projectInfo, def keyId, def keyFile) {
    TEMPLATE_FILE = 'jenkinsSshCredentials-template.xml'
    def JENKINS_CREDS_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"
    
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def curlCommand =
            "${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} -f ${curlUtils.XML_CONTEXT_HEADER} --data-binary @${JENKINS_CREDS_FILE}"
        
        sh(returnStdout: true, script: """
            ${shCmd.echo ''}
            cp ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} ${JENKINS_CREDS_FILE}
            sed -i -e 's/%UNIQUE_ID%/${keyId}/g' ${JENKINS_CREDS_FILE}
            set +x -v
            JENKINS_CREDS=\$(<${JENKINS_CREDS_FILE})
            echo "\${JENKINS_CREDS//%PRIVATE_KEY%/\$(<${keyFile})}" > ${JENKINS_CREDS_FILE}
            set -x +v

            ${curlCommand} ${projectInfo.jenkinsUrls.CREATE_CREDS}
            ${curlCommand} ${projectInfo.jenkinsUrls.UPDATE_CREDS}/${keyId}/config.xml

            rm -f ${JENKINS_CREDS_FILE}
        """)
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
        pushSshCredentialsToJenkins(projectInfo, keyId)
    }
    catch (Exception e) {
        echo "Creating ${keyId} on CICD failed, trying update"
    }
    pushSshCredentialsToJenkins(projectInfo, projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, keyId)

    keyId = el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    loggingUtils.echoBanner("PUSH ${keyId} CREDENTIALS TO CICD SERVER")
    jenkinsUrls = getJenkinsCredsUrls(projectInfo, keyId)
    try {
        pushSshCredentialsToJenkins(projectInfo, projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, keyId)
    }
    catch (Exception e) {
        echo "Creating ${keyId} on CICD failed, trying update"
    }
    pushSshCredentialsToJenkins(projectInfo, projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, keyId)

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

def deleteProjectDeployKeysFromJenkins(def projectInfo) {
    def jenkinsUrl =
        "https://jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/credentials/store/system/domain/_/credential/"

    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        projectInfo.components.each { component ->
            sh """
                ${shCmd.echo ''}
                ${curlUtils.getCmd(curlUtils.POST)} ${jenkinsUrl}/${component.gitRepoDeployKeyJenkinsId}/doDelete
            """
        }
    }
}

def pushImageRepositoryTokenToJenkins(def cicdJenkinsNamespace, def url, def tokenId) {
    def credsArray = [string(credentialsId: tokenId, variable: 'IMAGE_REPO_ACCESS_TOKEN',
                      string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN'))]
                      
    withCredentials(credsArray) {
        def curlCommand = """${curlUtils.getCmd(curlUtils.POST)} -H "content-type:application/xml" --data-binary @jenkinsTokenCredentials.xml ${url}"""
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

def createOrUpdatePipelines(def projectInfo, def pipelineFolderName, def pipelineDir, def pipelineFiles) {
    def msg = ["CREATING/UPDATING PIPELINES FROM THE FOLLOWING FILES IN THE JENKINS ${pipelineFolderName} FOLDER:"]
    msg.addAll(pipelineFiles.collect { it.name })
    loggingUtils.echoBanner(msg)
                
    def oldAutomationPipelines = listPipelinesInFolder(projectInfo, pipelineFolderName)
            
    createPipelinesFolder(projectInfo, pipelineFolderName)
    
    pipelineFiles.each { pipelineFile ->
        def pipelineName = pipelineFile.name.substring(0, pipelineFile.name.lastIndexOf('.'))
        oldAutomationPipelines.remove(pipelineName)
        createPipeline(projectInfo, pipelineFolderName, pipelineDir, pipelineFile)
    }
    
    oldAutomationPipelines.each { pipelineName ->
        deletePipeline(projectInfo, pipelineFolderName, pipelineName)
    }
}