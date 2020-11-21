/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the CICD framework
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def init() {
    pipelineUtils.echoBanner("COPYING ONBOARDING RESOURCES TO JENKINS AGENT")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/AuthBearerHeader-template.txt", text: libraryResource('templates/AuthBearerHeader-template.txt')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json", text: libraryResource('templates/githubSshCredentials-postfix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json", text: libraryResource('templates/githubSshCredentials-prefix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml", text: libraryResource('templates/jenkinsSshCredentials-postfix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml", text: libraryResource('templates/jenkinsSshCredentials-prefix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml", text: libraryResource('templates/jenkinsTokenCredentials-template.xml')
}

def deleteOldGithubKeys(def projectInfo, def isNonProd) {
    pipelineUtils.echoBanner("REMOVING OLD DEPLOY KEYS FROM GIT REPOS")

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        projectInfo.microServices.each { microService ->
            def fetchDeployKeyIdCurlCommand = scmScriptHelper.getCurlCommandGetDeployKeyIdFromScm(projectInfo, microService, isNonProd, GITHUB_ACCESS_TOKEN)
            def curlCommandToDeleteDeployKeyByIdFromScm =
                scmScriptHelper.getCurlCommandToDeleteDeployKeyByIdFromScm(projectInfo, microService, GITHUB_ACCESS_TOKEN)
            try {
                sh """
                    KEY_ID=\$(${fetchDeployKeyIdCurlCommand})
                    if [[ ! -z \${KEY_ID} ]]
                    then
                        ${shellEcho  '', 'REMOVING OLD DEPLOY KEY FROM GIT REPO: ${microService.gitRepoName}'}
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


def createAndPushPublicPrivateGithubRepoKeys(def projectInfo, def cicdRbacGroupJenkinsCredsUrls, def isNonProd) {
        pipelineUtils.echoBanner("CREATE PUBLIC/PRIVATE KEYS FOR EACH MICROSERVICE GIT REPO ACCESS",
                                 "PUSH EACH PUBLIC KEY FOR SCM REPO TO SCM HOST",
                                 "PUSH EACH PRIVATE KEY TO ${isNonProd ? 'NON-' : '' }PROD JENKINS")

        withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
            def credsFileName = 'scmSshCredentials.xml'
            def jenkinsCurlCommand = """
                curl -ksS -X POST -H "`cat ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt`" -H "content-type:application/xml" --data-binary @${credsFileName}"""

            def credsUrl = isNonProd ? cicdRbacGroupJenkinsCredsUrls.nonProdCicdJenkinsCredsUrl : cicdRbacGroupJenkinsCredsUrls.prodCicdJenkinsCredsUrl
            def createCredsCommand = "${jenkinsCurlCommand} ${credsUrl}"
            projectInfo.microServices.each { microService ->
                def pushDeployKeyIdCurlCommand =
                    scmScriptHelper.getScriptToPushDeployKeyToScm(projectInfo, microService, isNonProd, GITHUB_ACCESS_TOKEN)

                credsUrl = isNonProd ? cicdRbacGroupJenkinsCredsUrls.updateNonProdCicdJenkinsCredsUrl : cicdRbacGroupJenkinsCredsUrls.updateProdCicdJenkinsCredsUrl
                def updateCredsCommand = "${jenkinsCurlCommand} ${credsUrl}/${microService.gitSshPrivateKeyName}/config.xml"
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
                """
            }
        }
}

def createNamepaces(def projectInfo, def namespaces, def environments, def nodeSelectors) {
    pipelineUtils.echoBanner("SETUP OPENSHIFT NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", namespaces)
    
    sh """
        NODE_SELECTORS=(${nodeSelectors.join(' ')})
        ENVS=(${environments.join('  ')})
        NAMESPACES=(${namespaces.join(' ')})
        for i in \${!NAMESPACES[@]}
        do
            if [[ `oc projects | grep \${NAMESPACES[\$i]} | wc -l` -lt 1 ]]
            then
                if [[ \${NODE_SELECTORS[\$i]} != 'null' ]]
                then
                    oc adm new-project \${NAMESPACES[\$i]} --node-selector="\${NODE_SELECTORS[\$i]}"
                else
                    oc adm new-project \${NAMESPACES[\$i]}
                fi

                oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n \${NAMESPACES[\$i]}

                oc policy add-role-to-user edit system:serviceaccount:${projectInfo.nonProdCicdNamespace}:jenkins -n \${NAMESPACES[\$i]}

                oc adm policy add-cluster-role-to-user sealed-secrets-management system:serviceaccount:${projectInfo.nonProdCicdNamespace}:jenkins -n \${NAMESPACES[\$i]}
                oc adm policy add-cluster-role-to-user secrets-unsealer system:serviceaccount:${projectInfo.nonProdCicdNamespace}:jenkins -n \${NAMESPACES[\$i]}

                oc get sealedsecrets -l \${ENVS[\$i]}-env -o yaml -n ${el.cicd.EL_CICD_NON_PROD_MASTER_NAMEPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                    oc create -f - -n \${NAMESPACES[\$i]}

                ${shellEcho ''}
            fi
        done
    """
}
