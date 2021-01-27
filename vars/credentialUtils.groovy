/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

def getJenkinsCredsUrls(def projectInfoOrNamespace, def tokenId) {
    def cicdMasterNamespace = (projectInfoOrNamespace instanceof String || projectInfoOrNamespace instanceof GString) ?
        projectInfoOrNamespace : projectInfoOrNamespace.cicdMasterNamespace

    def jenkinsUrl = "https://jenkins-${cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    def createRelativePath = 'credentials/store/system/domain/_/createCredentials'
    def updateRelativePath = "credentials/store/system/domain/_/credential/${tokenId}/config.xml"

    def jenkinsCredsUrls = [:]

    jenkinsCredsUrls.createCredsUrl = "${jenkinsUrl}/${createRelativePath}"
    jenkinsCredsUrls.updateCredsUrl = "${jenkinsUrl}/${updateRelativePath}"

    return jenkinsCredsUrls
}

def copyElCicdMetaInfoBuildAndPullSecretsToGroupCicdServer(def projectInfo, def envs) {
    pipelineUtils.echoBanner("COPY el-CICD META-INFO AND ALL PULL SECRETS TO NAMESPACE ENVIRONMENTS FOR ${projectInfo.cicdMasterNamespace}")

    def pullSecretNames = envs.collect { el.cicd["${it}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"] }.toSet()
    sh """
        oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | \
            ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
            oc apply -f - -n ${projectInfo.cicdMasterNamespace}

        oc get secret ${el.cicd.EL_CICD_BUILD_SECRETS} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | \
            ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
            oc apply -f - -n ${projectInfo.cicdMasterNamespace}

        for PULL_SECRET_NAME in ${pullSecretNames.join(' ')}
        do
            oc get secrets \${PULL_SECRET_NAME} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | \
                ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                oc apply -f - -n ${projectInfo.cicdMasterNamespace}
        done
    """
}

def copySecretsFromElCicdMasterToGroupCicdServer(def projectInfo, def namespaces, def environments) {
    pipelineUtils.echoBanner("COPY PULL SECRETS TO NAMESPACE ENVIRONMENTS FOR ${projectInfo.id}:", namespaces.join(', '))

    sh """
        ENVS=(${environments.join(' ')})
        NAMESPACES=(${namespaces.join(' ')})
        for i in \${!NAMESPACES[@]}
        do
            oc get secrets -l \${ENVS[\${i}]}-env -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                oc apply -f - -n \${NAMESPACES[\${i}]}

            ${shellEcho ''}
        done
    """
}

def pushElCicdCredentialsToCicdServer(def projectInfo, def envs) {
    def keyId = el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    def jenkinsUrls = getJenkinsCredsUrls(projectInfo, keyId)
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, keyId)
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, keyId)

    keyId = el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    jenkinsUrls = getJenkinsCredsUrls(projectInfo, keyId)
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, keyId)
    pushSshCredentialsToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, keyId)

    envs.each { ENV ->
        def tokenId = el.cicd["${ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"]
        jenkinsUrls = getJenkinsCredsUrls(projectInfo, tokenId)
        pushImageRepositoryTokenToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.createCredsUrl, tokenId)
        pushImageRepositoryTokenToJenkins(projectInfo.cicdMasterNamespace, jenkinsUrls.updateCredsUrl, tokenId)
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

def deleteDeployKeysFromJenkins(def projectInfo) {
    def jenkinsUrl =
        "https://jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/credentials/store/system/domain/_/credential/"

    def curlCommand = 'curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)"'
    projectInfo.microServices.each { microService ->
        def doDelete = "${curlCommand} ${jenkinsUrl}/${microService.gitSshPrivateKeyName}/doDelete "
        sh """
            ${maskCommand(doDelete)}
        """
    }
}

def createAndPushPublicPrivateGithubRepoKeys(def projectInfo) {
    pipelineUtils.echoBanner("CREATE PUBLIC/PRIVATE KEYS FOR EACH MICROSERVICE GIT REPO ACCESS",
                             "PUSH EACH PUBLIC KEY FOR SCM REPO TO SCM HOST",
                             "PUSH EACH PRIVATE KEY TO THE el-CICD MASTER JENKINS")

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def credsFileName = 'scmSshCredentials.xml'
        def jenkinsCurlCommand =
            """curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)" -H "content-type:application/xml" --data-binary @${credsFileName}"""

        projectInfo.microServices.each { microService ->
            def bcExistsCommand = "oc get bc --ignore-not-found ${microService.id}-build-to-dev -n ${projectInfo.cicdMasterNamespace}"
            def bcExists = sh(returnStdout: true, script: bcExistsCommand)
            if (bcExists) {
                def pushDeployKeyIdCurlCommand = scmScriptHelper.getScriptToPushDeployKeyToScm(projectInfo, microService, 'GITHUB_ACCESS_TOKEN', false)

                def jenkinsUrls = getJenkinsCredsUrls(projectInfo, microService.gitSshPrivateKeyName)
                sh """
                    ${shellEcho  "ADDING PUBLIC KEY TO GIT REPO: ${microService.gitRepoName}"}
                    ssh-keygen -b 2048 -t rsa -f '${microService.gitSshPrivateKeyName}' -q -N '' -C 'Jenkins Deploy key for microservice' 2>/dev/null <<< y >/dev/null

                    ${pushDeployKeyIdCurlCommand}

                    ${shellEcho  '', "ADDING PRIVATE KEY FOR GIT REPO ON NON-PROD JENKINS: ${microService.name}"}
                    cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${microService.gitSshPrivateKeyName}/g" > ${credsFileName}
                    cat ${microService.gitSshPrivateKeyName} >> ${credsFileName}
                    cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${credsFileName}

                    ${maskCommand("${jenkinsCurlCommand} ${jenkinsUrls.createCredsUrl}")}
                    ${maskCommand("${jenkinsCurlCommand} ${jenkinsUrls.updateCredsUrl}")}

                    rm -f ${credsFileName}
                """
            }
        }
    }
}

def pushSshCredentialsToJenkins(def cicdJenkinsNamespace, def url, def keyId) {
    def SECRET_FILE_NAME = "${el.cicd.TEMP_DIR}/elcicdReadOnlyGithubJenkinsSshCredentials.xml"
    def credsArray = [sshUserPrivateKey(credentialsId: "${keyId}", keyFileVariable: "KEY_ID_FILE")]
    def curlCommand = """curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${url}"""
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

def pushImageRepositoryTokenToJenkins(def cicdJenkinsNamespace, def url, def tokenId) {
    withCredentials([string(credentialsId: tokenId, variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
        def curlCommand = """curl -ksS -X POST -H "Authorization: Bearer \$(oc whoami -t)" -H "content-type:application/xml" --data-binary @jenkinsTokenCredentials.xml ${url}"""
        sh """
            ${pipelineUtils.shellEchoBanner("PUSH IMAGE REPOSITORY TOKEN ${tokenId} TO ${cicdJenkinsNamespace} JENKINS")}

            cat ${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${tokenId}/g" > jenkinsTokenCredentials-named.xml
            cat jenkinsTokenCredentials-named.xml | sed "s|%TOKEN%|\${IMAGE_REPO_ACCESS_TOKEN}|g" > jenkinsTokenCredentials.xml

            ${maskCommand(curlCommand)}

            rm -f jenkinsTokenCredentials-named.xml jenkinsTokenCredentials.xml
        """
    }
}
