/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the el-CICD framework
 */

def init() {
    loggingUtils.echoBanner("COPYING ONBOARDING RESOURCES TO JENKINS AGENT")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json", text: libraryResource('templates/githubSshCredentials-postfix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json", text: libraryResource('templates/githubSshCredentials-prefix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml", text: libraryResource('templates/jenkinsSshCredentials-postfix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml", text: libraryResource('templates/jenkinsSshCredentials-prefix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml", text: libraryResource('templates/jenkinsTokenCredentials-template.xml')
    
    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        sh 'echo ${GITHUB_ACCESS_TOKEN} | gh auth login --with-token'
    }
}

def deleteNamespaces(def namespaces) {
    if (namespaces instanceof Collection) {
        namespaces = namespaces.join(' ')
    }

    sh """
        oc delete project --ignore-not-found ${namespaces}

        set +x
        COUNTER=1
        until [[ -z \$(oc get projects --no-headers --ignore-not-found ${namespaces}) ]]
        do
            printf -- '-%.0s' {1..\${COUNTER}}
            ${shCmd.echo ''}
            sleep 2
            let COUNTER+=1
        done
        set -x
    """
}

def createNamepace(def projectInfo, def namespace, def env, def nodeSelectors) {
    nodeSelectors = nodeSelectors ? "--node-selector='${nodeSelectors}'" : ''
    sh """
        if [[ -z \$(oc get projects --no-headers --ignore-not-found ${namespace}) ]]
        then
            ${shCmd.echo ''}
            oc adm new-project ${namespace} ${nodeSelectors}

            oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${namespace}

            oc policy add-role-to-user edit system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}

            oc adm policy add-cluster-role-to-user sealed-secrets-management \
                system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}
            oc adm policy add-cluster-role-to-user secrets-unsealer \
                system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}

            oc create sa jenkins-tester -n ${namespace}
        fi
    """
}

def applyResoureQuota(def projectInfo, def namespace, def resourceQuotaFile) {
    sh """
        ${shCmd.echo ''}
        QUOTAS=\$(oc get quota --ignore-not-found -l=projectid=${projectInfo.id} -o jsonpath='{.items[*].metadata.name}' -n ${namespace})
        if [[ ! -z \${QUOTAS} ]]
        then
            oc delete quota --wait --ignore-not-found \${QUOTAS} -n ${namespace}
            sleep 2
        fi
    """

    if (resourceQuotaFile) {
        dir(el.cicd.RESOURCE_QUOTA_DIR) {
            sh """
                ${shCmd.echo ''}
                oc apply -f ${resourceQuotaFile} -n ${namespace}
                oc label projectid=${projectInfo.id} -f ${resourceQuotaFile} -n ${namespace}
            """
        }
    }
}

def createNfsPersistentVolumes(def projectInfo, def isNonProd) {
    def pvNames = [:]
    if (projectInfo.microServices && projectInfo.nfsShares) {
        loggingUtils.echoBanner("SETUP NFS PERSISTENT VOLUMES:", projectInfo.nfsShares.collect { it.claimName }.join(', '))

        dir(el.cicd.OKD_TEMPLATES_DIR) {
            projectInfo.nfsShares.each { nfsShare ->
                def envs = isNonProd ? projectInfo.nonProdEnvs : [projectInfo.prodEnv]
                envs.each { env ->
                    if (nfsShare.envs.contains(env)) {
                        namespace = isNonProd ? projectInfo.nonProdNamespaces[env] : projectInfo.prodNamespace
                        pvName = "${el.cicd.NFS_PV_PREFIX}-${namespace}-${nfsShare.claimName}"
                        createNfsShare(projectInfo, namespace, pvName, nfsShare)

                        pvNames[pvName] = true
                    }
                }
            }
        }
    }

    loggingUtils.echoBanner("REMOVE UNNEEDED, AVAILABLE AND RELEASED ${projectInfo.id} NFS PERSISTENT VOLUMES, IF ANY")
    def releasedPvs = sh(returnStdout: true, script: """
            ${shCmd.echo ''}
            oc get pv -l projectid=${projectInfo.id} --ignore-not-found | egrep 'Released|Available' | awk '{ print \$1 }'
        """).split('\n').findAll { it.trim() }

    releasedPvs.each { pvName ->
        if (!pvNames[pvName]) {
            sh """
                ${shCmd.echo ''}
                oc delete pv ${pvName}
            """
        }
    }
}

def createNfsShare(def projectInfo, def namespace, def pvName, def nfsShare) {
    sh """
        ${shCmd.echo ''}
        oc process --local \
                   -f nfs-pv-template.yml \
                   -l projectid=${projectInfo.id}\
                   -p PV_NAME=${pvName} \
                   -p CAPACITY=${nfsShare.capacity} \
                   -p ACCESS_MODE=${nfsShare.accessMode} \
                   -p NFS_EXPORT=${nfsShare.exportPath} \
                   -p NFS_SERVER=${nfsShare.server} \
                   -p CLAIM_NAME=${nfsShare.claimName} \
                   -p NAMESPACE=${namespace} \
            | oc apply -f -
    """
}

def createAndPushPublicPrivateSshKeys(def projectInfo) {
    loggingUtils.echoBanner("CREATE PUBLIC/PRIVATE KEYS FOR EACH MICROSERVICE GIT REPO ACCESS",
                             "PUSH EACH PUBLIC KEY FOR SCM REPO TO SCM HOST",
                             "PUSH EACH PRIVATE KEY TO THE el-CICD MASTER JENKINS")

    withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
        def credsFileName = 'scmSshCredentials.xml'

        withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN_ID, variable: 'JENKINS_ACCESS_TOKEN')]) {
            projectInfo.components.each { component ->
                sh """
                    ssh-keygen -b 2048 -t rsa -f '${component.gitSshPrivateKeyName}' \
                        -q -N '' -C 'Jenkins Deploy key for microservice' 2>/dev/null <<< y >/dev/null"
                    
                    ${shCmd.echo  '', "ADDING PRIVATE KEY FOR GIT REPO ON CICD JENKINS: ${component.name}"}
                    cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${component.gitSshPrivateKeyName}/g" > ${credsFileName}
                    cat ${component.gitSshPrivateKeyName} >> ${credsFileName}
                    cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${credsFileName}
                """
                
                githubUtils.pushDeployKey(component)
                
                jenkinsUtils.pushDeployKey(component)
            
                def pushDeployKeyIdCurlCommand = createScriptToPushDeployKey(projectInfo, component, 'GITHUB_ACCESS_TOKEN', false)

                def jenkinsUrls = getJenkinsCredsUrls(projectInfo, component.gitSshPrivateKeyName)
                sh "rm -f ${credsFileName} ${component.gitSshPrivateKeyName} ${component.gitSshPrivateKeyName}.pub"
            }
        }
    }
}

def copyPullSecretsToEnvNamespace(def namespace, def env) {
    def secretName = el.cicd["${env.toUpperCase()}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]
    sh """
        ${shCmd.echo ''}
        oc get secrets ${secretName} -o yaml -n ${el.cicd.ONBOARDING_MASTER_NAMESPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
            oc apply -f - -n ${namespace}

        ${shCmd.echo ''}
    """
}


def generateBuildPipelineFiles(def projectInfo) {
    dir (el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR) {
        projectInfo.microServices.each { microService ->
            sh """
                cp build-to-dev.xml.template ${microService.name}-build-to-dev.xml
                sed -i -e "s/%PROJECT_ID%/${projectInfo.id}|g;" \
                    -e "s/%MICROSERVICE_NAME%/${microService.name}/g;" \
                    -e "s/%GIT_BRANCH%/${projectInfo.gitBranch}/g" \
                    -e "s/%CODE_BASE%/${microService.codeBase}/g" \
                    -e "s/%DEV_NAMESPACE%/${projectInfo.devNamespace}/g" \
                    ${microService.name}-build-to-dev.xml
            """
        }
        
        projectInfo.libraries.each { library ->
            sh """
                cp build-library.xml.template ${library.name}-build-library.xml
                sed -i -e "s/%PROJECT_ID%/${projectInfo.id}|g;" \
                    -e "s/%LIBRARY_NAME%/${library.name}/g;" \
                    -e "s/%GIT_BRANCH%/${projectInfo.gitBranch}/g" \
                    -e "s/%CODE_BASE%/${library.codeBase}/g" \
                    ${library.name}-build-library.xml
            """
        }
    }
}