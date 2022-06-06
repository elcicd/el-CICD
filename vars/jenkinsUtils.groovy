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
def SYSTEM_DOMAIN_CREDS_BASE_PATH = 'credentials/store/system/domain/_'

@Field
def CREATE_CREDS_PATH = "${SYSTEM_DOMAIN_CREDS_BASE_PATH}/createCredentials"

@Field
def SYSTEM_DOMAIN_CREDS_PATH = "${SYSTEM_DOMAIN_CREDS_BASE_PATH}/credential"

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
    projectInfo.jenkinsUrls.UPDATE_CREDS = "${projectInfo.jenkinsUrls.HOST}/${SYSTEM_DOMAIN_CREDS_PATH}"
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
            ${curlUtils.getCmd(curlUtils.DELETE, 'JENKINS_ACCESS_TOKEN')} ${projectInfo.jenkinsUrls.ACCESS_FOLDER}/${folderName}/${JOB}/${pipelineName}/
        """
    }
}

def copyElCicdCredentialsToCicdServer(def projectInfo, def ENVS) {
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

    def tokenIds = []
    ENVS.each { ENV ->
        def tokenId = el.cicd["${ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"]
        if (!tokenIds.contains(tokenId)) {
            loggingUtils.shellEchoBanner("PUSH ${tokenId} CREDENTIALS TO CICD SERVER")

            pushImageRepositoryTokenToJenkins(projectInfo, tokenId)

            tokenIds.add(tokenId)
        }
    }
}

def pushSshCredentialsToJenkins(def projectInfo, def keyId, def keyFile) {
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
            ${sshKeyVar}=\$(echo \${${sshKeyVar}} | sed -e :a -e '/^\n*$/{$d;N;};/\n$/ba')
            echo "\${JENKINS_CREDS//%PRIVATE_KEY%/\${\$(<${sshKeyVar})}}" > ${JENKINS_CREDS_FILE}
            set -x
            
            cat ${JENKINS_CREDS_FILE}
            
            ${curlCommand} ${projectInfo.jenkinsUrls.CREATE_CREDS}
            ${curlCommand} -f ${projectInfo.jenkinsUrls.UPDATE_CREDS}/${keyId}/config.xml

            rm -f ${JENKINS_CREDS_FILE}
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

def deleteProjectDeployKeyFromJenkins(def projectInfo, def component) {
    withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        sh """
            ${shCmd.echo ''}
            ${curlUtils.getCmd(curlUtils.POST)} ${projectInfo.jenkinsUrls.DELETE_CREDS}/${component.gitDeployKeyJenkinsId}/doDelete
        """
    }
}

def pushImageRepositoryTokenToJenkins(def projectInfo, def tokenId) {
    withCredentials([string(credentialsId: tokenId, variable: 'IMAGE_REPO_ACCESS_TOKEN'),
                     string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
        def JENKINS_CREDS_FILE = "${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials.xml"
        def curlCommand =
            "${curlUtils.getCmd(curlUtils.POST, 'JENKINS_ACCESS_TOKEN')} ${curlUtils.XML_CONTEXT_HEADER} --data-binary @${JENKINS_CREDS_FILE}"
        
        sh """
            ${shCmd.echo ''}
            cat ${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | \
                sed "s/%ID%/${tokenId}/; s|%TOKEN%|\${IMAGE_REPO_ACCESS_TOKEN}|" > ${JENKINS_CREDS_FILE}

            ${curlCommand} ${projectInfo.jenkinsUrls.CREATE_CREDS}
            ${curlCommand} -f ${projectInfo.jenkinsUrls.UPDATE_CREDS}/${tokenId}/config.xml

            rm -f ${JENKINS_CREDS_FILE}
        """
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