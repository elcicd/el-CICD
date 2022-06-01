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

def createAndPushPublicPrivateSshKeys(def projectInfo) {
    loggingUtils.echoBanner("CREATE PUBLIC/PRIVATE KEYS FOR EACH MICROSERVICE GIT REPO ACCESS",
                             "PUSH EACH PUBLIC KEY FOR SCM REPO TO SCM HOST",
                             "PUSH EACH PRIVATE KEY TO THE el-CICD MASTER JENKINS")

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def credsFileName = 'scmSshCredentials.xml'
        def jenkinsCurlCommand =
            """${getJenkinsCurlCommand('POST')} -H "content-type:application/xml" --data-binary @${credsFileName}"""

        withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
            projectInfo.components.each { component ->
                def pushDeployKeyIdCurlCommand = createScriptToPushDeployKey(projectInfo, component, 'GITHUB_ACCESS_TOKEN', false)

                def jenkinsUrls = getJenkinsCredsUrls(projectInfo, component.gitSshPrivateKeyName)
                sh """
                    ${shCmd.echo  '', "ADDING PUBLIC KEY TO GIT REPO: ${component.gitRepoName}"}
                    ssh-keygen -b 2048 -t rsa -f '${component.gitSshPrivateKeyName}' -q -N '' -C 'Jenkins Deploy key for microservice' 2>/dev/null <<< y >/dev/null

                    ${pushDeployKeyIdCurlCommand}

                    ${shCmd.echo  '', "ADDING PRIVATE KEY FOR GIT REPO ON CICD JENKINS: ${component.name}"}
                    cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${component.gitSshPrivateKeyName}/g" > ${credsFileName}
                    cat ${component.gitSshPrivateKeyName} >> ${credsFileName}
                    cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${credsFileName}

                    ${jenkinsCurlCommand} ${jenkinsUrls.createCredsUrl}
                    ${jenkinsCurlCommand} ${jenkinsUrls.updateCredsUrl}

                    rm -f ${credsFileName} ${component.gitSshPrivateKeyName} ${component.gitSshPrivateKeyName}.pub
                """
            }
        }
    }
}

def deleteSshKeys(def projectInfo) {
    loggingUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM GIT REPOS")

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        projectInfo.components.each { component ->
            def fetchDeployKeyIdCurlCommand = createScriptGetDeployKeyId(projectInfo, component, 'GITHUB_ACCESS_TOKEN')
            def curlCommandToDeleteDeployKeyByIdFromScm =
                createScriptToDeleteDeployKeyById(projectInfo, component, 'GITHUB_ACCESS_TOKEN')
            try {
                sh """
                    ${shCmd.echo ''}
                    KEY_IDS=\$(${fetchDeployKeyIdCurlCommand})
                    if [[ ! -z \${KEY_IDS} ]]
                    then
                        for KEY_ID in \${KEY_IDS}
                        do
                            ${shCmd.echo  '', "REMOVING OLD DEPLOY KEY FROM GIT REPO: ${component.gitRepoName}"}
                            ${curlCommandToDeleteDeployKeyByIdFromScm}/\${KEY_ID}
                        done
                    else
                        ${shCmd.echo  '', "OLD DEPLOY KEY NOT FOUND: ${component.gitRepoName}"}
                    fi
                """
            }
            catch (Exception e) {
                loggingUtils.errorBanner("EXCEPTION: CHECK WHETHER GIT REPO NAMES ARE PROPERLY DEFINED IN PROJECT-INFO", e.getMessage())
            }
        }
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

def createScriptGetDeployKeyId(def projectInfo, def component, def ACCESS_TOKEN) {
    def curlCommand

    if (projectInfo.scmHost.contains('github')) {
        def deployKeyName = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}|${projectInfo.id}"
        def url = "https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys"
        def jqIdFilter = """jq '.[] | select(.title  == "${deployKeyName}") | .id'"""

        curlCommand = "${CURL_GET} ${url} | ${jqIdFilter}"
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        loggingUtils.errorBanner("GitLab is not supported yet")
    }

    return curlCommand
}

def createScriptToDeleteDeployKeyById(def projectInfo, def component, def ACCESS_TOKEN) {
    def curlCommand

    if (projectInfo.scmHost.contains('github')) {
        curlCommand = "curl -ksS -X DELETE https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys"
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        loggingUtils.errorBanner("GitLab is not supported yet")
    }

    return curlCommand
}

def createScriptToPushDeployKey(def projectInfo, def component, def ACCESS_TOKEN, def readOnly) {
    def curlCommand

    if (projectInfo.scmHost.contains('github')) {
        def deployKeyName = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}|${projectInfo.id}"
        def secretFile = "${el.cicd.TEMP_DIR}/sshKeyFile.json"
        readOnly = readOnly ? 'true' : 'false'

        def url = "https://\${${ACCESS_TOKEN}}@${projectInfo.scmRestApiHost}/repos/${projectInfo.scmOrganization}/${component.gitRepoName}/keys"
        curlCommand = """
            cat ${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json | sed 's/%DEPLOY_KEY_NAME%/${deployKeyName}/' > ${secretFile}
            cat ${component.gitSshPrivateKeyName}.pub >> ${secretFile}
            cat ${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json >> ${secretFile}
            sed -i -e "s/%READ_ONLY%/${readOnly}/" ${secretFile}

            ${CURL_POST} ${APPLICATION_JSON_HDR} ${GIT_HUB_REST_API_HDR} -d @${secretFile} ${url}
        """
    }
    else if (projectInfo.scmHost.contains('gitlab')) {
        loggingUtils.errorBanner("GitLab is not supported yet")
    }

    return curlCommand
}

