/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

def getJenkinsCredsUrl(def namespace) {
    def protocol = "https"
    def app = "jenkins"
    def createRelativePath = 'credentials/store/system/domain/_/createCredentials'
    def updateRelativePath = 'credentials/store/system/domain/_/credential'

    def cicdRbacGroupJenkinsCredsUrls = [:]

    cicdRbacGroupJenkinsCredsUrls.cicdJenkinsCreateCredsUrl =
        "${protocol}://${app}-${namespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/${createRelativePath}"
    cicdRbacGroupJenkinsCredsUrls.cicdJenkinsUpdateCredsUrl =
        "${protocol}://${app}-${namespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/${updateRelativePath}"

    return cicdRbacGroupJenkinsCredsUrls
}

def pushElCicdCredentialsToCicdServer(def projectInfo, def envs) {
    def credsUrls = getJenkinsCredsUrl(projectInfo.cicdMasterNamespace)

    def key = el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, credsUrls.cicdJenkinsCreateCredsUrl, key)
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, credsUrls.cicdJenkinsUpdateCredsUrl, key)

    key = el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, credsUrls.cicdJenkinsCreateCredsUrl, key)
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, credsUrls.cicdJenkinsUpdateCredsUrl, key)

    projectInfo.envs.each { ENV ->
        def tokenIdKey = "${ENV}${IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"
        pushImageRepositoryTokenToJenkins(projectInfo.cicdMasterNamespace, el.cicd[tokenIdKey], credsUrls.cicdJenkinsCreateCredsUrl)
        pushImageRepositoryTokenToJenkins(projectInfo.cicdMasterNamespace, el.cicd[tokenIdKey], credsUrls.cicdJenkinsUpdateCredsUrl)
    }
}

def deleteDeployKeysFromGithub(def projectInfo) {
    pipelineUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM GIT REPOS")

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        projectInfo.microServices.each { microService ->
            def fetchDeployKeyIdCurlCommand = scmScriptHelper.getCurlCommandGetDeployKeyIdFromScm(projectInfo, microService, 'GITHUB_ACCESS_TOKEN')
            def curlCommandToDeleteDeployKeyByIdFromScm =
                scmScriptHelper.getCurlCommandToDeleteDeployKeyByIdFromScm(projectInfo, microService, 'GITHUB_ACCESS_TOKEN')
            try {
                sh """
                    KEY_ID=\$(${fetchDeployKeyIdCurlCommand})
                    if [[ ! -z \${KEY_ID} ]]
                    then
                        ${shellEcho  '', "REMOVING OLD DEPLOY KEY FROM GIT REPO: ${microService.gitRepoName}"}
                        ${curlCommandToDeleteDeployKeyByIdFromScm}/\${KEY_ID}
                    else
                        ${shellEcho  "OLD DEPLOY KEY NOT FOUND: ${microService.gitRepoName}"}
                    fi
                """
            }
            catch (Exception e) {
                pipelineUtils.errorBanner("EXCEPTION: CHECK WHETHER GIT REPO NAMES ARE PROPERLY DEFINED IN PROJECT-INFO", e.getMessage())
            }
        }
    }
}

def deleteDeployKeysFromJenkins(def projectInfo, def namespace) {
    def credsUrls = getJenkinsCredsUrl(cicdNamespace)

    def curlCommand = 'curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)'
    projectInfo.microServices.each { microService ->
        def doDelete = """${curlCommand} ${credsUrls.cicdJenkinsUpdateCredsUrl}/${microService.gitSshPrivateKeyName}/doDelete """
        sh """
            ${maskCommand(doDelete)}
        """
    }
}

def createAndPushPublicPrivateGithubRepoKeys(def projectInfo, def cicdNamespace) {
    pipelineUtils.echoBanner("CREATE PUBLIC/PRIVATE KEYS FOR EACH MICROSERVICE GIT REPO ACCESS",
                                "PUSH EACH PUBLIC KEY FOR SCM REPO TO SCM HOST",
                                "PUSH EACH PRIVATE KEY TO ${isNonProd ? 'NON-' : '' }PROD JENKINS")

    def credsUrls = getJenkinsCredsUrl(cicdNamespace)

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def credsFileName = 'scmSshCredentials.xml'
        def jenkinsCurlCommand = """
            curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)" -H "content-type:application/xml" --data-binary @${credsFileName}"""

        def createCredsCommand = "${jenkinsCurlCommand} ${credsUrls.cicdJenkinsCreateCredsUrl}"
        projectInfo.microServices.each { microService ->
            def pushDeployKeyIdCurlCommand = scmScriptHelper.getScriptToPushDeployKeyToScm(projectInfo, microService, 'GITHUB_ACCESS_TOKEN', false)

            def updateCredsCommand = "${jenkinsCurlCommand} ${credsUrls.cicdJenkinsUpdateCredsUrl}/${microService.gitSshPrivateKeyName}/config.xml"
            sh """
                ${shellEcho  "ADDING PUBLIC KEY TO GIT REPO: ${microService.gitRepoName}"}
                ssh-keygen -b 2048 -t rsa -f '${microService.gitSshPrivateKeyName}' -q -N '' -C 'Jenkins Deploy key for microservice' 2>/dev/null <<< y >/dev/null

                ${pushDeployKeyIdCurlCommand}

                ${shellEcho  '', "ADDING PRIVATE KEY FOR GIT REPO ON NON-PROD JENKINS: ${microService.name}"}
                cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${microService.gitSshPrivateKeyName}/g" > ${credsFileName}
                cat ${microService.gitSshPrivateKeyName} >> ${credsFileName}
                cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${credsFileName}

                ${maskCommand(createCredsCommand)}
                ${maskCommand(updateCredsCommand)}

                rm -f ${credsFileName}
            """
        }
    }
}

def pushSshCredentialsToJenkins(def cicdJenkinsNamespace, def cicdJenkinsUrl, def keyId) {
    def SECRET_FILE_NAME = "${el.cicd.TEMP_DIR}/elcicdReadOnlyGithubJenkinsSshCredentials.xml"
    def credsArray = [sshUserPrivateKey(credentialsId: "${keyId}", keyFileVariable: "KEY_ID_FILE")]
    def curlCommand = """curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${cicdJenkinsUrl}"""
    withCredentials(credsArray) {
        sh """
            ${pipelineUtils.shellEchoBanner("PUSH SSH GIT REPO PRIVATE KEY TO ${cicdJenkinsNamespace} JENKINS")}

            cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed 's/%UNIQUE_ID%/${keyId}/g' > ${SECRET_FILE_NAME}
            cat \${KEY_ID_FILE} >> ${SECRET_FILE_NAME}
            cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE_NAME}

            ${maskCommand(curlCommand)}

            rm -f ${SECRET_FILE_NAME}
        """
    }
}

def pushImageRepositoryTokenToJenkins(def cicdJenkinsNamespace, def credentialsId, def cicdJenkinsUrl) {
    withCredentials([string(credentialsId: credentialsId, variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
        def curlCommand = """curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)" -H "content-type:application/xml" --data-binary @jenkinsTokenCredentials.xml ${cicdJenkinsUrl}"""
        sh """
            ${pipelineUtils.shellEchoBanner("PUSH IMAGE REPOSITORY TOKEN ${credentialsId} TO ${cicdJenkinsNamespace} JENKINS")}

            cat ${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${credentialsId}/g" > jenkinsTokenCredentials-named.xml
            cat jenkinsTokenCredentials-named.xml | sed "s|%TOKEN%|\${IMAGE_REPO_ACCESS_TOKEN}|g" > jenkinsTokenCredentials.xml

            ${maskCommand(curlCommand)}

            rm -f jenkinsTokenCredentials-named.xml jenkinsTokenCredentials.xml
        """
    }
}
