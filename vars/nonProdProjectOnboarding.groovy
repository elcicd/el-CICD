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

    onboardingUtils.createNfsPersistentVolumes(projectInfo, true)

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
        pipelineUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", projectInfo.nonProdNamespaces.values().join(', '))

        def nodeSelectors = projectInfo.NON_PROD_ENVS.collectEntries { ENV ->
            [ENV.toLowerCase(), el.cicd["${ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: '']
        }

        projectInfo.nonProdNamespaces.each { env, namespace ->
            onboardingUtils.createNamepace(projectInfo, namespace, env, nodeSelectors[env])

            credentialUtils.copyPullSecretsToEnvNamespace(namespace, env)

            def resourceQuotaFile = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas.default

            applyResoureQuota(projectInfo, namespace, resourceQuotaFile)
        }
    }

    stage('Setup openshift sandbox environments') {
        if (projectInfo.sandboxEnvs > 0) {
            def devNodeSelector = el.cicd["${projectInfo.DEV_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: ''
            def resourceQuotaFile = projectInfo.resourceQuotas[projectInfo.devEnv] ?: projectInfo.resourceQuotas.default

            projectInfo.sandboxNamespaces.each { namespace ->
                onboardingUtils.createNamepaces(projectInfo, namespace, projectInfo.devEnv, devNodeSelector)

                credentialUtils.copyPullSecretsToEnvNamespace(namespace, projectInfo.devEnv)

                applyResoureQuota(projectInfo, namespace, resourceQuotaFile)
            }
        }
    }

    stage('Apply ResourceQuotas on all project namespaces') {
        onboardingUtils.applyNonProdResourceQuotas(projectInfo)

        onboardingUtils.applySandboxResourceQuotas(projectInfo)
    }

    stage('Delete old github public keys with curl') {
        credentialUtils.deleteDeployKeysFromGithub(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        credentialUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
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