/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/project-onboarding-pipeline-template
 */

import java.nio.file.Path
import java.nio.file.Paths


def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    verticalJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, true)

    stage('Remove stale namespace environments and pipelines if necessary') {
        def namespacesToDelete = args.rebuildNonProd ? projectInfo.nonProdNamespaces.values().join(' ') : ''

        sh """
            ${pipelineUtils.shellEchoBanner("REMOVING STALE PIPELINES FOR ${projectInfo.id}, IF ANY")}

            for BCS in `oc get bc -l projectid=${projectInfo.id} -n ${projectInfo.cicdMasterNamespace} | grep Jenkins | awk '{print \$1}'`
            do
                while [ `oc get bc \${BCS} -n ${projectInfo.cicdMasterNamespace} | grep \${BCS} | wc -l` -gt 0 ] ;
                do
                    oc delete bc \${BCS} --ignore-not-found -n ${projectInfo.cicdMasterNamespace}
                    sleep 3
                    ${shellEcho ''}
                done
            done

            ${ args.rebuildNonProd ? pipelineUtils.shellEchoBanner("REMOVING STALE NON-PROD ENVIRONMENT(S) FOR ${projectInfo.id}") : ''}

            ${args.rebuildNonProd ? "oc delete project ${namespacesToDelete} || true" : ''}

            NAMESPACES_TO_DELETE='${namespacesToDelete}'
            for NAMESPACE in \${NAMESPACES_TO_DELETE}
            do
                until
                    !(oc project \${NAMESPACE} > /dev/null 2>&1)
                do
                    sleep 3
                done
            done
        """
    }

    stage('Add build-to-dev pipeline for each Github repo on non-prod Jenkins') {
        pipelineUtils.echoBanner("ADD BUILD AND DEPLOY PIPELINE FOR EACH EL_CICD_GIT_PROVIDER REPO ON NON-PROD JENKINS FOR ${projectInfo.id}")

        writeFile file:"${el.cicd.BUILDCONFIGS_DIR}/build-to-dev-pipeline-template.yml",
                  text: libraryResource("buildconfigs/build-to-dev-pipeline-template.yml")

        dir (el.cicd.BUILDCONFIGS_DIR) {
            projectInfo.microServices.each { microService ->
                sh """
                    oc process --local \
                               -f build-to-dev-pipeline-template.yml \
                               -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} \
                               -p PROJECT_ID=${projectInfo.id} \
                               -p MICROSERVICE_GIT_REPO=${microService.gitRepoUrl} \
                               -p MICROSERVICE_NAME=${microService.name} \
                               -p DEPLOY_TO_NAMESPACE=${projectInfo.devNamespace} \
                               -p GIT_BRANCH=${projectInfo.gitBranch} \
                               -p CODE_BASE=${microService.codeBase} \
                               -n ${projectInfo.cicdMasterNamespace} \
                        | oc create -f - -n ${projectInfo.cicdMasterNamespace}

                    ${shellEcho ''}
                """
            }
        }
    }

    stage('Setup openshift namespace environments') {
        def nodeSelectors = projectInfo.NON_PROD_ENVS.collect { ENV ->
            return el.cicd["${ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: ''
        }

        onboardingUtils.createNamepaces(projectInfo,
                                        projectInfo.nonProdNamespaces.values(),
                                        projectInfo.nonProdNamespaces.keySet(),
                                        nodeSelectors)
    }

    stage('Setup openshift sandbox environments') {
        if (projectInfo.sandboxEnvs > 0) {
            def sandboxNamespacePrefix = "${projectInfo.id}-${el.cicd.SANDBOX_NAMESPACE_BADGE}"

            namespaces = []
            envs = []
            nodeSelectors = []

            (1..projectInfo.sandboxEnvs).each { i ->
                namespaces += "${sandboxNamespacePrefix}-${i}"
                envs += projectInfo.devEnv
                nodeSelectors += el.cicd["${projectInfo.DEV_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: ''
            }

            onboardingUtils.createNamepaces(projectInfo,
                                            namespaces,
                                            envs,
                                            nodeSelectors)
        }
    }

    stage('Delete old github public keys with curl') {
        credentialsUtils.deleteDeployKeysFromGithub(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        credentialsUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
    }

    stage('Push Webhook to GitHub for non-prod Jenkins') {
        pipelineUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO EL_CICD_GIT_PROVIDER FOR EACH REPO")

        withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
            projectInfo.microServices.each { microService ->
                scriptToPushWebhookToScm =
                    scmScriptHelper.getScriptToPushWebhookToScm(projectInfo, microService, 'GITHUB_ACCESS_TOKEN')
                sh """
                    ${shellEcho  "GIT REPO NAME: ${microService.gitRepoName}"}

                    ${scriptToPushWebhookToScm}
                """
            }
        }
    }
}