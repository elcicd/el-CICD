/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def syncJenkinsPipelines(def projectInfo) {
    def baseAgentImage = "${el.cicd.JENKINS_OCI_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"
    
    sh """
        ${shCmd.echo '', "SYNCING pipeline definitions for the CICD Server in ${projectInfo.teamInfo.cicdMasterNamespace}"}
        if [[ ! -z \$(helm list --short --filter sync-jenkins-pipelines -n ${projectInfo.teamInfo.cicdMasterNamespace}) ]]
        then
            helm uninstall sync-jenkins-pipelines -n ${projectInfo.teamInfo.cicdMasterNamespace}
        fi

        ${shCmd.echo ''}
        helm upgrade --wait --wait-for-jobs --install --history-max=1 \
            --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
            --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${el.cicd.JENKINS_CONFIG_FILE_PATH} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/sync-jenkins-pipelines-job-values.yaml \
            -n ${projectInfo.teamInfo.cicdMasterNamespace} \
            sync-jenkins-pipelines \
            ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    """
}

def removeGitDeployKeysFromProject(def projectInfo) {
    projectInfoUtils.setRemoteRepoDeployKeyId(projectInfo)

    def buildStages =  concurrentUtils.createParallelStages('Setup GIT deploy keys', projectInfo.modules) { module ->
        withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
            dir(module.workDir) {
                def moduleId = "${projectInfo.teamInfo.id}/${projectInfo.id}/${module.gitRepoName}"
                sh """
                    set +x
                    echo
                    echo "--> REMOVING GIT DEPLOY KEY FOR: ${moduleId}"
                    echo

                    source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                    ${getDeleteDeployKeyFunctionCall(projectInfo, module)}

                    set -x
                """
            }
        }
    }

    parallel(buildStages)
}

def createNewGitDeployKeysForProject(def projectInfo) {
    projectInfoUtils.setRemoteRepoDeployKeyId(projectInfo)

    def buildStages =  concurrentUtils.createParallelStages('Setup GIT deploy keys', projectInfo.modules) { module ->
        withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
            def moduleId = "${projectInfo.teamInfo.id}/${projectInfo.id}/${module.gitRepoName}"
            dir(module.workDir) {
                sh """
                    set +x
                    echo
                    echo "--> CREATING NEW GIT DEPLOY KEY FOR: ${moduleId}"
                    echo

                    source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                    ${getDeleteDeployKeyFunctionCall(projectInfo, module)}

                    _add_git_repo_deploy_key ${projectInfo.gitRestApiHost} \
                                             ${projectInfo.gitOrganization} \
                                             ${module.gitRepoName} \
                                             \${GIT_ACCESS_TOKEN} \
                                             '${projectInfo.repoDeployKeyId}' \
                                             ${module.gitDeployKeyJenkinsId} \
                                             false
                    set -x
                """
            }
        }
    }

    parallel(buildStages)
}

def removeGitWebhooksFromProject(def projectInfo) {
    def buildStages =  concurrentUtils.createParallelStages('Setup GIT webhooks', projectInfo.modules) { module ->
        if (!module.disableWebhook) {
            withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
                def moduleId = "${projectInfo.teamInfo.id}/${projectInfo.id}/${module.gitRepoName}"
                dir(module.workDir) {
                    sh """
                        set +x
                        echo
                        echo "--> REMOVING GIT WEBOOK FOR: ${moduleId}"
                        echo

                        source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                        ${getDeleteWebhookFunctionCall(projectInfo, module)}

                        set -x
                    """
                }
            }
        }
        else {
            echo "-->  WARNING: WEBHOOK FOR ${module.name} MARKED AS DISABLED: SKIPPING"
        }
    }

    parallel(buildStages)
}

def createNewGitWebhooksForProject(def projectInfo) {
    def buildStages =  concurrentUtils.createParallelStages('Setup GIT webhooks', projectInfo.modules) { module ->
        if (!module.disableWebhook) {
            withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
                def moduleId = "${projectInfo.teamInfo.id}/${projectInfo.id}/${module.gitRepoName}"
                dir(module.workDir) {
                    sh """
                        set +x
                        echo
                        echo "--> CREATING NEW WEBOOK FOR: ${moduleId}"
                        echo

                        source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                        ${getDeleteWebhookFunctionCall(projectInfo, module)}

                        _add_webhook ${projectInfo.gitRestApiHost} \
                                    ${projectInfo.gitOrganization} \
                                    ${module.gitRepoName} \
                                    ${projectInfo.jenkinsHostUrl} \
                                    ${projectInfo.id} \
                                    ${module.name} \
                                    ${module.isComponent ? 'build-component' : 'build-artifact'} \
                                    ${module.gitDeployKeyJenkinsId} \
                                    \${GIT_ACCESS_TOKEN}
                        set -x
                    """
                }
            }
        }
        else {
            echo "-->  WARNING: WEBHOOK FOR ${module.name} MARKED AS DISABLED: SKIPPING"
        }
    }

    parallel(buildStages)
}

def getDeleteDeployKeyFunctionCall(def projectInfo, def module) {
    return """
        _delete_git_repo_deploy_key ${projectInfo.gitRestApiHost} \
                                    ${projectInfo.gitOrganization} \
                                    ${module.gitRepoName} \
                                    \${GIT_ACCESS_TOKEN} \
                                    '${projectInfo.repoDeployKeyId}'
    """
}

def getDeleteWebhookFunctionCall(def projectInfo, def module) {
    return """
        _delete_webhook ${projectInfo.gitRestApiHost} \
                        ${projectInfo.gitOrganization} \
                        ${module.gitRepoName} \
                        ${projectInfo.jenkinsHostUrl} \
                        ${projectInfo.id} \
                        ${module.name} \
                        ${module.isComponent ? 'build-component' : 'build-artifact'} \
                        '${module.gitDeployKeyJenkinsId}' \
                        \${GIT_ACCESS_TOKEN}
    """
}
