/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for creating curl commands needed for onboarding projects into
 * an el-CICD managed CICD pipeline.
 */

import groovy.transform.Field

@Field
def GITHUB_REST_API_HDR = "-H 'Accept: application/vnd.github.v3+json'"

def deleteProjectDeployKeys(def projectInfo, def component) {
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def jqIdFilter = """jq '.[] | select(.title  == "${projectInfo.gitRepoDeployKeyId}") | .id'"""
        
        def url = "https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys"
        sh """
            ${shCmd.echo ''}
            KEY_IDS=\$(${curlUtils.getCmd(curlUtils.GET, 'GITHUB_ACCESS_TOKEN', false)} -f ${curlUtils.FAIL_SILENT} ${url} | ${jqIdFilter})
            if [[ ! -z \${KEY_IDS} ]]
            then
                ${shCmd.echo  '', "REMOVING OLD DEPLOY KEY FROM GIT REPO: ${component.gitRepoName}"}
                for KEY_ID in \${KEY_IDS}
                do
                    ${curlUtils.getCmd(curlUtils.DELETE, 'GITHUB_ACCESS_TOKEN')} ${url}/\${KEY_ID}
                done
            else
                ${shCmd.echo  '', "OLD DEPLOY KEY NOT FOUND: ${component.gitRepoName}"}
            fi
        """
    }
}

def addProjectDeployKey(def projectInfo, def component, def keyFile) {
    TEMPLATE_FILE = 'githubDeployKey-template.json'
    def GITHUB_CREDS_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"
    
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def curlCmd = curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN')
        
        sh """
            cp ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} ${GITHUB_CREDS_FILE}
            sed -i -e 's/%DEPLOY_KEY_NAME%/${component.gitRepoDeployKeyJenkinsId}/g' ${GITHUB_CREDS_FILE}
            set +x -v
            GITHUB_CREDS=\$(<${GITHUB_CREDS_FILE})
            echo "\${GITHUB_CREDS//%DEPLOY_KEY%/\$(<${keyFile})}" > ${GITHUB_CREDS_FILE}
            set -x +v
            sed -i -e "s/%READ_ONLY%/false}/" ${GITHUB_CREDS_FILE}
            
            ${curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN')} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys \
                -d @${GITHUB_CREDS_FILE}
            
            rm -f ${GITHUB_CREDS_FILE}
        """
    }
}

def createBuildWebhooks(def projectInfo) {
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def buildComponents = []
        buildComponents.addAll(projectInfo.microServices)
        buildComponents.addAll(projectInfo.libraries)

        buildComponents.each { component ->
            if (!component.gitMicroServiceRepos) {
                scriptToPushWebhookToScm = createScriptPushWebhook(projectInfo, component, 'GITHUB_ACCESS_TOKEN')
                sh """
                    ${shCmd.echo  "GIT REPO NAME: ${component.gitRepoName}"}

                    ${scriptToPushWebhookToScm}
                """
            }
        }
    }
}

def createScriptPushWebhook(def projectInfo, def component, def ACCESS_TOKEN) {
    def curlCommand

    if (projectInfo.scmHost.contains('github')) {
        def url = "https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/hooks"

        def webhookFile = "${el.cicd.TEMP_DIR}/githubWebhook.json"
        curlCommand = """
            BC_SELF_LINK=\$(oc get bc -l component=${component.name} -o jsonpath='{.items[0].metadata.selfLink}' -n ${projectInfo.cicdMasterNamespace})
            cat ${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json | \
              sed -e "s|%HOSTNAME%|${el.cicd.CLUSTER_API_HOSTNAME}|"   \
                  -e "s|%BC_SELF_LINK%|\${BC_SELF_LINK}|"   \
                  -e "s|%COMPONENT_ID%|${component.id}|" > ${webhookFile}

            curl -ksS -X POST ${APPLICATION_JSON_HDR} ${GIT_HUB_REST_API_HDR} -d @${webhookFile} ${url}
        """
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        loggingUtils.errorBanner("GitLab is not supported yet")
    }

    return curlCommand
}

