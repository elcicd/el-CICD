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
                ${shCmd.echo  '', "REMOVING OLD DEPLOY KEY(S) FROM ${component.gitRepoName}: \${KEY_IDS}"}
                for KEY_ID in \${KEY_IDS}
                do
                    ${curlUtils.getCmd(curlUtils.DELETE, 'GITHUB_ACCESS_TOKEN', false)} ${url}/\${KEY_ID}
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
        sh """
            set +x
            cp ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} ${GITHUB_CREDS_FILE}
            sed -i -e 's/%DEPLOY_KEY_NAME%/${projectInfo.gitRepoDeployKeyId}/g' ${GITHUB_CREDS_FILE}
            GITHUB_CREDS=\$(<${GITHUB_CREDS_FILE})
            echo "\${GITHUB_CREDS//%DEPLOY_KEY%/\$(<${keyFile})}" > ${GITHUB_CREDS_FILE}
            set -x
            
            ${curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys \
                -d @${GITHUB_CREDS_FILE}
            
            rm -f ${GITHUB_CREDS_FILE}
        """
    }
}

def pushBuildWebhook(def projectInfo, def component, def buildType) {
    TEMPLATE_FILE = 'githubWebhook-template.json'
    def WEBHOOK_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"
        
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        sh """
            ${shCmd.echo  "GIT WEBHOOK FOR: ${component.gitRepoName}"}
            
            cat ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} | \
              sed -e "s|%HOSTNAME%|${projectInfo.jenkinsUrls.HOST}|" \
                  -e "s|%PROJECT_ID%|${projectInfo.id}|" \
                  -e "s|%COMPONENT_ID%|${component.id}|" \
                  -e "s|%BUILD_TYPE%|${buildType}|" \
                  -e "s|%WEB_TRIGGER_AUTH_TOKEN%|${component.gitDeployKeyJenkinsId}|" > ${WEBHOOK_FILE}
                  
            WEBHOOK=\$(cat ${WEBHOOK_FILE} | jq '.config.url')
                  
            HOOK_IDS=${curlUtils.getCmd(curlUtils.GET, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/hooks |
                jq '.[] | select(.config.url  == "\${WEBHOOK}") | .id'
                
            for HOOK_ID in \${HOOK_IDS}
            do
                ${curlUtils.getCmd(curlUtils.DELETE, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                    https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/hooks/\${HOOK_ID} 
            done
            
            
            ${curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/hooks \
                -d @${WEBHOOK_FILE}
            
            rm -f ${WEBHOOK_FILE}
        """
    }
}

