/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for creating curl commands needed for onboarding projects into
 * an el-CICD managed CICD pipeline.
 */

import groovy.transform.Field

@Field
def GITHUB_REST_API_HDR = "-H 'Accept: application/vnd.github.v3+json'"

def deleteProjectDeployKeys(def projectInfo, def module) {
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def jqIdFilter = """jq '.[] | select(.title  == "${projectInfo.repoDeployKeyId}") | .id'"""
        
        def url = "https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${module.scmRepoName}/keys"
        sh """
            ${shCmd.echo ''}
            KEY_IDS=\$(${curlUtils.getCmd(curlUtils.GET, 'GITHUB_ACCESS_TOKEN', false)} -f ${curlUtils.FAIL_SILENT} ${url} | ${jqIdFilter})
            if [[ ! -z \${KEY_IDS} ]]
            then
                ${shCmd.echo  '', "REMOVING OLD DEPLOY KEY(S) FROM ${module.scmRepoName}: \${KEY_IDS}", ''}
                for KEY_ID in \${KEY_IDS}
                do
                    ${curlUtils.getCmd(curlUtils.DELETE, 'GITHUB_ACCESS_TOKEN', false)} ${url}/\${KEY_ID}
                done
            else
                ${shCmd.echo  '', "OLD DEPLOY KEY NOT FOUND: ${module.scmRepoName}"}
            fi
        """
    }
}

def addProjectDeployKey(def projectInfo, def module, def keyFile) {
    TEMPLATE_FILE = 'githubDeployKey-template.json'
    def GITHUB_CREDS_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"
    
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {        
        sh """
             ${shCmd.echo  '', "CREATING NEW GIT DEPLOY KEY FOR: ${module.scmRepoName}", ''}
             
            set +x
            cp ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} ${GITHUB_CREDS_FILE}
            sed -i -e 's/%DEPLOY_KEY_TITLE%/${projectInfo.repoDeployKeyId}/g' ${GITHUB_CREDS_FILE}
            GITHUB_CREDS=\$(<${GITHUB_CREDS_FILE})
            echo "\${GITHUB_CREDS//%DEPLOY_KEY%/\$(<${keyFile})}" > ${GITHUB_CREDS_FILE}
            set -x
            
            ${curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${module.scmRepoName}/keys \
                -d @${GITHUB_CREDS_FILE} | jq 'del(.key)'
                
             ${shCmd.echo  '', "GIT DEPLOY KEY CREATED FOR: ${module.scmRepoName}", ''}
            
            rm -f ${GITHUB_CREDS_FILE}
        """
    }
}

def pushBuildWebhook(def projectInfo, def module, def buildType) {
    TEMPLATE_FILE = 'githubWebhook-template.json'
    def WEBHOOK_FILE = "${el.cicd.TEMP_DIR}/${TEMPLATE_FILE}"
        
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        sh """
            ${shCmd.echo  '', "CREATING NEW GIT WEBHOOK FOR: ${module.scmRepoName}", ''}
            
            cat ${el.cicd.TEMPLATES_DIR}/${TEMPLATE_FILE} | \
              sed -e "s|%HOSTNAME%|${projectInfo.jenkinsUrls.HOST}|" \
                  -e "s|%PROJECT_ID%|${projectInfo.id}|" \
                  -e "s|%COMPONENT_ID%|${module.id}|" \
                  -e "s|%BUILD_TYPE%|${buildType}|" \
                  -e "s|%WEB_TRIGGER_AUTH_TOKEN%|${module.gitDeployKeyJenkinsId}|" > ${WEBHOOK_FILE}
                  
            WEBHOOK=\$(cat ${WEBHOOK_FILE} | jq '.config.url')
                  
            HOOK_IDS=\$(${curlUtils.getCmd(curlUtils.GET, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${module.scmRepoName}/hooks |
                jq ".[] | select(.config.url  == "\${WEBHOOK}") | .id")
                
            for HOOK_ID in \${HOOK_IDS}
            do
                ${curlUtils.getCmd(curlUtils.DELETE, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                    https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${module.scmRepoName}/hooks/\${HOOK_ID} 
            done
            
            
            HOOK_ID=\$(${curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN', false)} ${GITHUB_REST_API_HDR} \
                https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${module.scmRepoName}/hooks \
                -d @${WEBHOOK_FILE} | jq '.id')
            
            ${shCmd.echo  '', "NEW GIT WEBHOOK ID CREATED: \${HOOK_ID}", ''}
            
            rm -f ${WEBHOOK_FILE}
        """
    }
}

