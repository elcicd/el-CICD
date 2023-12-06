/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def syncJenkinsPipelines(def teamInfo) {
    def baseAgentImage = "${el.cicd.JENKINS_OCI_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${el.cicd.JENKINS_AGENT_DEFAULT}"

    sh """
        ${shCmd.echo '', "--> SYNCING pipeline definitions for the CICD Server in ${teamInfo.cicdMasterNamespace}"}
        if [[ ! -z \$(helm list --short --filter sync-jenkins-pipelines -n ${teamInfo.cicdMasterNamespace}) ]]
        then
            helm uninstall sync-jenkins-pipelines -n ${teamInfo.cicdMasterNamespace}
        fi

        ${shCmd.echo ''}
        helm upgrade --wait --wait-for-jobs --install --history-max=1 \
            --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${baseAgentImage} \
            --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${el.cicd.JENKINS_CONFIG_FILE_PATH} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/sync-jenkins-pipelines-job-values.yaml \
            -n ${teamInfo.cicdMasterNamespace} \
            sync-jenkins-pipelines \
            ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    """
}

def uninstallSdlcEnvironments(def projectInfo) {
    sh """
        HELM_CHART=\$(helm list -q -n ${projectInfo.teamInfo.cicdMasterNamespace} --filter "${projectInfo.id}-${el.cicd.ENVIRONMENTS_POSTFIX}")
        if [[ "\${HELM_CHART}" ]]
        then
            helm uninstall --wait ${projectInfo.id}-${el.cicd.ENVIRONMENTS_POSTFIX} -n ${projectInfo.teamInfo.cicdMasterNamespace}
            sleep 2
        else
            ${shCmd.echo "--> SDLC environments for project ${projectInfo.id} not installed; Skipping..."}
        fi
    """
}

def removeGitDeployKeysFromProject(def moduleList) {
    concurrentUtils.runParallelStages('Setup GIT deploy keys', moduleList) { module ->
        withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
            dir(module.workDir) {
                sh """
                    set +x
                    echo
                    echo "--> REMOVING GIT DEPLOY KEY FOR: ${getLongformModuleId(module)}"
                    echo

                    source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                    ${getDeleteDeployKeyFunctionCall(module)}

                    set -x
                """
            }
        }
    }
}

def createNewGitDeployKeysForProject(def moduleList) {
    concurrentUtils.runParallelStages('Setup GIT deploy keys', moduleList) { module ->
        withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
            def projectInfo = module.projectInfo
            dir(module.workDir) {
                sh """
                    set +x
                    echo
                    echo "--> CREATING NEW GIT DEPLOY KEY FOR: ${getLongformModuleId(module)}"
                    echo

                    source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                    ${getDeleteDeployKeyFunctionCall(module)}

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
}

def removeGitWebhooksFromProject(def moduleList) {
    concurrentUtils.runParallelStages('Setup GIT webhooks', moduleList) { module ->
        if (!module.disableWebhook) {
            withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
                dir(module.workDir) {
                    sh """
                        set +x
                        echo
                        echo "--> REMOVING GIT WEBOOK FOR: ${getLongformModuleId(module)}"
                        echo

                        source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                        ${getDeleteWebhookFunctionCall(module)}

                        set -x
                    """
                }
            }
        }
        else {
            echo "-->  WARNING: WEBHOOK FOR ${module.name} MARKED AS DISABLED: SKIPPING"
        }
    }
}

def createNewGitWebhooksForProject(def moduleList) {
    concurrentUtils.runParallelStages('Setup GIT webhooks', moduleList) { module ->
        if (!module.disableWebhook) {
            withCredentials([string(credentialsId: el.cicd.EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID, variable: 'GIT_ACCESS_TOKEN')]) {
                def projectInfo = module.projectInfo
                dir(module.workDir) {
                    sh """
                        set +x
                        echo
                        echo "--> CREATING NEW WEBOOK FOR: ${getLongformModuleId(module)}"
                        echo

                        source ${el.cicd.EL_CICD_SCRIPTS_DIR}/github-utilities.sh

                        ${getDeleteWebhookFunctionCall(module)}

                        _add_webhook ${projectInfo.gitRestApiHost} \
                                     ${projectInfo.gitOrganization} \
                                     ${module.gitRepoName} \
                                     ${projectInfo.jenkinsHostUrl} \
                                     ${projectInfo.id} \
                                     ${module.name} \
                                     ${module.isComponent ? el.cicd.BUILD_COMPONENT_PIPELINE_SUFFIX : el.cicd.BUILD_ARTIFACT_PIPELINE_SUFFIX} \
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
}

def getDeleteDeployKeyFunctionCall(def module) {
    def projectInfo = module.projectInfo
    return """
        _delete_git_repo_deploy_key ${projectInfo.gitRestApiHost} \
                                    ${projectInfo.gitOrganization} \
                                    ${module.gitRepoName} \
                                    \${GIT_ACCESS_TOKEN} \
                                    '${projectInfo.repoDeployKeyId}'
    """
}

def getDeleteWebhookFunctionCall(def module) {
    def projectInfo = module.projectInfo
    return """
        _delete_webhook ${projectInfo.gitRestApiHost} \
                        ${projectInfo.gitOrganization} \
                        ${module.gitRepoName} \
                        ${projectInfo.jenkinsHostUrl} \
                        ${projectInfo.id} \
                        ${module.name} \
                        ${module.isComponent ? el.cicd.BUILD_COMPONENT_PIPELINE_SUFFIX : el.cicd.BUILD_ARTIFACT_PIPELINE_SUFFIX} \
                        '${module.gitDeployKeyJenkinsId}' \
                        \${GIT_ACCESS_TOKEN}
    """
}

def createModuleSshKeys(def modules) {
    modules.each { module ->
        dir(module.workDir) {
            echo "Creating deploy key for ${module.gitRepoName}"

            sh "ssh-keygen -b 2048 -t rsa -f '${module.gitDeployKeyJenkinsId}' -q -N '' 2>/dev/null <<< y >/dev/null"
        }
    }
}

def getLongformModuleId(def module) {
    def projectInfo = module.projectInfo
    def teamInfo = projectInfo.teamInfo
    return "${teamInfo.id}:${projectInfo.id}:${module.gitRepoName}"
}
