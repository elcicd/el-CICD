/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
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

def getCurlCommandGetDeployKeyIdFromScm(def projectInfo, def microService, def ACCESS_TOKEN) {
    def curlCommand

    def deployKeyName = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}-${projectInfo.id}"
    if (projectInfo.scmHost.contains('github')) {
        def url = "https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/keys"
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
        curlCommand = "curl -ksS -X DELETE https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/keys"
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        pipelineUtils.errorBanner("GitLab is not supported yet")
    }
    else if (projectInfo.scmHost.contains('bitbucket')) {
        pipelineUtils.errorBanner("Bitbucket is not supported yet")
    }

    return curlCommand
}

def getScriptToPushDeployKeyToScm(def projectInfo, def microService, def ACCESS_TOKEN, def readOnly) {
    def curlCommand

    def deployKeyName = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}-${projectInfo.id}"
    def secretFile = "${el.cicd.TEMP_DIR}/sshKeyFile.json"
    readOnly = readOnly ? 'true' : 'false'
    if (projectInfo.scmHost.contains('github')) {
        def url = "https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/keys"
        curlCommand = """
            cat ${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json | sed 's/%DEPLOY_KEY_NAME%/${deployKeyName}/' > ${secretFile}
            cat ${microService.gitSshPrivateKeyName}.pub >> ${secretFile}
            cat ${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json >> ${secretFile}
            sed -i -e "s/%READ_ONLY%/${readOnly}/" ${secretFile}

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

    if (projectInfo.scmHost.contains('github')) {
        def url = "https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${microService.gitRepoName}/hooks"

        def HOSTNAME = sh(returnStdOut: true, script: """oc config current-context | awk -F '/' '{print \$2 }'""")
        def webhookFile = "${el.cicd.TEMP_DIR}/githubWebhook.json"
        curlCommand = """
            BC_SELF_LINK=\$(oc get bc -l microservice=${microService.name} -o jsonpath='{.items[0].metadata.selfLink}' -n ${projectInfo.cicdMasterNamespace})
            cat ${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json | \
              sed -e "s|%HOSTNAME%|${el.cicd.CLUSTER_API_HOSTNAME}|"   \
                  -e "s|%BC_SELF_LINK%|\${BC_SELF_LINK}|"   \
                  -e "s|%MICROSERVICE_ID%|${microService.id}|" > ${webhookFile}

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

