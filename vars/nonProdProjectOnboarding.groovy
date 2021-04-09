/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    verticalJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, true)

    onboardingUtils.createNfsPersistentVolumes(projectInfo, true)

    stage('Remove stale namespace environments if requested') {
        onboardingUtils.cleanStalePipelines(projectInfo)

        if (projectInfo.microServices) {
            if (args.rebuildNonProd || args.rebuildSandboxes) {
                def namespacesToDelete = args.rebuildNonProd ? el.cicd.nonProdEnvs.collect { "${projectInfo.id}-${it}" } : []
                pipelineUtils.echoBanner("REMOVING STALE NON-PROD ENVIRONMENT(S) FOR ${projectInfo.id}:", namespacesToDelete.join(' '))

                def nsRegex = "${projectInfo.id}-${el.cicd.SANDBOX_NAMESPACE_BADGE}-[0-9]+|${projectInfo.id}-${el.cicd.HOTFIX_NAMESPACE_BADGE}"
                sh """
                    SBXS=\$(oc projects | egrep '${nsRegex}' | tr '\n' ' ')
                    until [[ -z \$(oc delete projects --ignore-not-found ${namespacesToDelete.join(' ')} \${SBXS}) ]]
                    do
                        ${shellEcho ''}
                        sleep 3
                    done
                """
            }
        }
        else if (args.rebuildNonProd || args.rebuildSandboxes) {
            pipelineUtils.echoBanner("NO MICROSERVICES DEFINED IN PROJECT: NO PROJECT NAMESPACES TO REMOVE")
        }
    }

    stage('Add build-to-dev and/or build-library pipelines for each Github repo on non-prod Jenkins') {
        pipelineUtils.echoBanner("ADD BUILD AND DEPLOY PIPELINE FOR EACH MICROSERVICE GIT REPO USED BY ${projectInfo.id}")

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

        writeFile file:"${el.cicd.BUILDCONFIGS_DIR}/build-library-pipeline-template.yml",
                  text: libraryResource("buildconfigs/build-library-pipeline-template.yml")

        dir (el.cicd.BUILDCONFIGS_DIR) {
            projectInfo.libraries.each { library ->
                sh """
                    oc process --local \
                               -f build-library-pipeline-template.yml \
                               -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} \
                               -p PROJECT_ID=${projectInfo.id} \
                               -p LIBRARY_GIT_REPO=${library.gitRepoUrl} \
                               -p LIBRARY_NAME=${library.name} \
                               -p GIT_BRANCH=${projectInfo.gitBranch} \
                               -p CODE_BASE=${library.codeBase} \
                               -n ${projectInfo.cicdMasterNamespace} \
                        | oc create -f - -n ${projectInfo.cicdMasterNamespace}

                    ${shellEcho ''}
                """
            }
        }
    }

    stage('Setup OKD namespace environments') {
        if (projectInfo.microServices) {
            pipelineUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", projectInfo.nonProdNamespaces.values().join(', '))

            def nodeSelectors = projectInfo.NON_PROD_ENVS.collectEntries { ENV ->
                [ENV.toLowerCase(), el.cicd["${ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: '']
            }

            projectInfo.nonProdNamespaces.each { env, namespace ->
                onboardingUtils.createNamepace(projectInfo, namespace, env, nodeSelectors[env])

                credentialUtils.copyPullSecretsToEnvNamespace(namespace, env)

                def resourceQuotaFile = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas.default
                onboardingUtils.applyResoureQuota(projectInfo, namespace, resourceQuotaFile)
            }
        }
        else {
            pipelineUtils.echoBanner("NO MICROSERVICES DEFINED IN PROJECT: NO PROJECT NAMESPACES TO SETUP")
        }
    }

    stage('Setup OKD sandbox and/or hotfix environment(s)') {
        if (projectInfo.microServices && (projectInfo.sandboxEnvs > 0 || projectInfo.hotfixNamespace)) {
            def namespaces = []
            namespaces.addAll(projectInfo.sandboxNamespaces)
            if (projectInfo.allowsHotfixes) {
                namespaces.addAll(projectInfo.hotfixNamespace)
            }

            pipelineUtils.echoBanner("Setup OKD sandbox and/or hotfix environment(s):", namespaces.join(', '))

            def devNodeSelector = el.cicd["${projectInfo.DEV_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: ''
            def resourceQuotaFile = projectInfo.resourceQuotas.sandbox ?: projectInfo.resourceQuotas.default

            namespaces.each { namespace ->
                onboardingUtils.createNamepace(projectInfo, namespace, projectInfo.devEnv, devNodeSelector)

                credentialUtils.copyPullSecretsToEnvNamespace(namespace, projectInfo.devEnv)

                onboardingUtils.applyResoureQuota(projectInfo, namespace, resourceQuotaFile)
            }
        }
    }

    stage('Delete old github public keys with curl') {
        credentialUtils.deleteDeployKeysFromGithub(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        credentialUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
    }

    stage('Push Webhook to GitHub for non-prod Jenkins') {
        pipelineUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO EACH GIT REPO")

        withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
            projectInfo.components.each { component ->
                scriptToPushWebhookToScm =
                    scmScriptHelper.getScriptToPushWebhookToScm(projectInfo, component, 'GITHUB_ACCESS_TOKEN')
                sh """
                    ${shellEcho  "GIT REPO NAME: ${component.gitRepoName}"}

                    ${scriptToPushWebhookToScm}
                """
            }
        }
    }
}