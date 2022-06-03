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
    def GITHUB_DEPLOY_KEY_FILE = 'githubDeployKey-template.json'
    def SECRET_FILE_NAME = "${el.cicd.TEMP_DIR}/${GITHUB_DEPLOY_KEY_FILE}"
    def url = "https://${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys"
    
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def curlCmd = curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN')
        
        sh """
            GITHUB_DEPLOY_KEY=\$(sed -e 's/%DEPLOY_KEY_NAME%/${component.gitRepoDeployKeyJenkinsId}/g' ${el.cicd.TEMPLATES_DIR}/${GITHUB_DEPLOY_KEY_FILE}})
            set +x -v; echo "\${GITHUB_DEPLOY_KEY//%DEPLOY_KEY%/\$(<${keyFile})}" > ${SECRET_FILE_NAME}; set -x +v
            sed -i -e "s/%READ_ONLY%/false}/" ${SECRET_FILE_NAME}
            
            ${curlUtils.getCmd(curlUtils.POST, 'GITHUB_ACCESS_TOKEN')} ${GITHUB_REST_API_HDR} \
                https://${apiHost}/repos/${org}/${repoName}/keys \
                -d @${SECRET_FILE_NAME}
            
            rm -f ${SECRET_FILE_NAME}
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

