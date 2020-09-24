/*
 * Utility methods for creating curl commands needed for onboarding projects into
 * an el-CICD managed CICD pipeline.
 */

import groovy.transform.Field

@Field
def CURL_GET = 'curl -ksS -X GET'

@Field
def CURL_POST = 'curl -ksS -X POST'

@Field
def CURL_DELETE = 'curl -ksS -X DELETE'

@Field
def GIT_HUB_REST_API_HDR = '-H Accept:application/vnd.github.v3+json'

@Field
def APPLICATION_JSON_HDR = '-H application:json'

def getDeployKeyName(def projectInfo, def isNonProd) {
    def keyPrefix = isNonProd ? el.cicd.EL_CICD_DEPLOY_NON_PROD_KEY_TITLE : el.cicd.EL_CICD_DEPLOY_PROD_KEY_TITLE
    return "${keyPrefix}-${projectInfo.id}"
}

def getCurlCommandGetDeployKeyIdFromScm(def projectInfo, def microService, def isNonProd, def ACCESS_TOKEN) {
    def curlCommand

    def deployKeyName = getDeployKeyName(projectInfo, isNonProd)
    if (projectInfo.scmHost.contains('github')) {
        def url = "https://${ACCESS_TOKEN}@api.${projectInfo.scmHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/keys"
        def jqIdFilter = """jq '.[] | select(.title  == "${deployKeyName}") | .id'"""

        curlCommand = "${CURL_GET} ${url} | ${jqIdFilter}"
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        pipelineUtils.errorBanner("GitLab is not supported yet")
    }
    else if (projectInfo.scmHost.contains('bitbucket')) {
        pipelineUtils.errorBanner("Bitbucket is not supported yet")
    }

    return curlCommand
}

def getCurlCommandToDeleteDeployKeyByIdFromScm(def projectInfo, def microService, def ACCESS_TOKEN) {
    def curlCommand

    if (projectInfo.scmHost.contains('github')) {
        curlCommand = "curl -ksS -X DELETE https://${ACCESS_TOKEN}@api.${projectInfo.scmHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/keys"
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        pipelineUtils.errorBanner("GitLab is not supported yet")
    }
    else if (projectInfo.scmHost.contains('bitbucket')) {
        pipelineUtils.errorBanner("Bitbucket is not supported yet")
    }

    return curlCommand
}

def getScriptToPushDeployKeyToScm(def projectInfo, def microService, def isNonProd, def ACCESS_TOKEN) {
    def curlCommand

    def deployKeyName = getDeployKeyName(projectInfo, isNonProd)
    def secretFile = "${el.cicd.TEMP_DIR}/sshKeyFile.json"
    if (projectInfo.scmHost.contains('github')) {
        def url = "https://${ACCESS_TOKEN}@api.${projectInfo.scmHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/keys"
        curlCommand = """
            cat ${el.cicd.EL_CICD_DIR}/resources/githubSshCredentials-prefix.json | sed 's/%DEPLOY_KEY_NAME%/${deployKeyName}/' > ${el.cicd.TEMP_DIR}/sshKeyFile.json
            cat ${microService.gitSshPrivateKeyName}.pub >> ${el.cicd.TEMP_DIR}/sshKeyFile.json
            cat ${el.cicd.EL_CICD_DIR}/resources/githubSshCredentials-postfix.json >> ${secretFile}

            ${CURL_POST} ${APPLICATION_JSON_HDR} ${GIT_HUB_REST_API_HDR} -d @${secretFile} ${url}
        """
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        pipelineUtils.errorBanner("GitLab is not supported yet")
    }
    else if (projectInfo.scmHost.contains('bitbucket')) {
        pipelineUtils.errorBanner("Bitbucket is not supported yet")
    }

    return curlCommand
}

def getScriptToPushWebhookToScm(def projectInfo, def microService, def ACCESS_TOKEN) {
    def curlCommand

    def bcName = "BC_NAME=`oc get bc -l microservice=${microService.name} -o jsonpath='{.items[0].metadata.name}' -n ${projectInfo.nonProdCicdNamespace}`"

    if (projectInfo.scmHost.contains('github')) {
        def url = "https://${ACCESS_TOKEN}@api.${projectInfo.scmHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/hooks"

        def webhookFile = "${el.cicd.TEMP_DIR}/githubWebhook.json"
        curlCommand = """
            ${bcName}
            cat ${el.cicd.EL_CICD_DIR}/resources/githubWebhook-template.json | \
              sed -e "s/%HOSTNAME%/api.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/g"   \
                  -e "s/%GROUP_NAME%/${projectInfo.rbacGroup}/g"   \
                  -e "s/%PROJECT_ID%-%MICROSERVICE_NAME%/${microService.id}/g"  \
                  -e "s/%BC_NAME%/\${BC_NAME}/g" > ${webhookFile}


            curl -ksS -X POST ${APPLICATION_JSON_HDR} ${GIT_HUB_REST_API_HDR} -d @${webhookFile} ${url}
        """
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        pipelineUtils.errorBanner("GitLab is not supported yet")
    }
    else if (projectInfo.scmHost.contains('bitbucket')) {
        pipelineUtils.errorBanner("Bitbucket is not supported yet")
    }

    return curlCommand
}

