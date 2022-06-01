/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    verticalJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, true)

    dir (el.cicd.EL_CICD_DIR) {
        git url: el.cicd.EL_CICD_GIT_REPO,
            branch: el.cicd.EL_CICD_CONFIG_GIT_REPO_BRANCH_NAME,
            credentialsId: el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    }
    verticalJenkinsCreationUtils.refreshGeneralAutomationPipelines(projectInfo, true)

    onboardingUtils.createNfsPersistentVolumes(projectInfo, true)

    stage('Remove stale namespace environments if requested') {
        loggingUtils.echoBanner("REMOVING STALE NAMESPACES FOR ${projectInfo.id}, IF REQUESTED")

        if (args.rebuildNonProd || args.rebuildSandboxes) {
            def namespacesToDelete = []
            namespacesToDelete.addAll(projectInfo.sandboxNamespaces.values())
            if (args.rebuildNonProd) {
                namespacesToDelete.addAll(projectInfo.nonProdNamespaces.values())
                namespacesToDelete.add(projectInfo.hotfixNamespace)
            }

            onboardingUtils.deleteNamespaces(namespacesToDelete)
        }
    }

    stage('Clean stale build-to-dev pipelines') {
        onboardingUtils.cleanStaleBuildToDevPipelines(projectInfo)
    }

    stage('Add build-to-dev and/or build-library pipelines for each Github repo on non-prod Jenkins') {
        loggingUtils.echoBanner("ADD BUILD AND DEPLOY PIPELINE FOR EACH MICROSERVICE GIT REPO USED BY ${projectInfo.id}")

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

                    ${shCmd.echo ''}
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

                    ${shCmd.echo ''}
                """
            }
        }
    }

    stage('Setup OKD namespace environments') {
        if (projectInfo.microServices) {
            loggingUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:",
                                      projectInfo.nonProdNamespaces.values().join(', '))

            def nodeSelectors = projectInfo.NON_PROD_ENVS.collectEntries { ENV ->
                [ENV.toLowerCase(), el.cicd["${ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: '']
            }

            projectInfo.nonProdNamespaces.each { env, namespace ->
                onboardingUtils.createNamepace(projectInfo, namespace, env, nodeSelectors[env])

                onboardingUtils.copyPullSecretsToEnvNamespace(namespace, env)

                def resourceQuotaFile = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas.default
                onboardingUtils.applyResoureQuota(projectInfo, namespace, resourceQuotaFile)
            }
        }
        else {
            loggingUtils.echoBanner("NO MICROSERVICES DEFINED IN PROJECT: NO PROJECT NAMESPACES TO SETUP")
        }
    }

    stage('Setup OKD sandbox environment(s)') {
        if (projectInfo.microServices && (projectInfo.sandboxEnvs.size() > 0 || projectInfo.hotfixNamespace)) {
            loggingUtils.echoBanner("Setup OKD sandbox environment(s):", projectInfo.sandboxNamespaces.values().join(', '))

            def devNodeSelector = el.cicd["${projectInfo.DEV_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]?.replaceAll(/\s/, '') ?: ''
            def resourceQuotaFile = projectInfo.resourceQuotas.sandbox ?: projectInfo.resourceQuotas.default

            projectInfo.sandboxNamespaces.each { env, namespace ->
                onboardingUtils.createNamepace(projectInfo, namespace, projectInfo.devEnv, devNodeSelector)

                onboardingUtils.copyPullSecretsToEnvNamespace(namespace, projectInfo.devEnv)

                onboardingUtils.applyResoureQuota(projectInfo, namespace, resourceQuotaFile)
            }
        }
    }

    stage('Delete old github public keys with curl') {
        githubUtils.deleteSshKeys(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        onboardingUtils.createAndPushPublicPrivateSshKeys(projectInfo)
    }

    stage('Push Webhook to GitHub for non-prod Jenkins') {
        loggingUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO EACH GIT REPO")

        githubUtils.createBuildWebhooks(projectInfo)
    }
}