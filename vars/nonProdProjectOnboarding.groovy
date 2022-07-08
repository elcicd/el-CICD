/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo

    cicdJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, true)

    stage('refresh automation pipelines') {
        jenkinsUtils.configureCicdJenkinsUrls(projectInfo)
        
        def pipelineFiles
        dir(el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR) {
            pipelineFiles = findFiles(glob: "**/*.xml")
        }
        
        jenkinsUtils.createOrUpdatePipelines(projectInfo, el.cicd.NON_PROD_AUTOMATION, el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR, pipelineFiles)
    }
    
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

    stage('Add build-to-dev and/or build-library pipelines for each Github repo on non-prod Jenkins') {
        loggingUtils.echoBanner("ADD BUILD AND DEPLOY PIPELINE FOR EACH MICROSERVICE GIT REPO USED BY ${projectInfo.id}")

        onboardingUtils.generateBuildPipelineFiles(projectInfo)
        
        def buildPipelineFiles
        dir(el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR) {
            buildPipelineFiles = findFiles(glob: "**/*-build-*.xml")
        }
        
        jenkinsUtils.createOrUpdatePipelines(projectInfo, projectInfo.id, el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR, buildPipelineFiles)
        
        dir (el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR) {
            sh 'rm -f *-build-*.xml'
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

    manageDeployKeys([projectInfo: projectInfo, isNonProd: true])

    stage('Push Webhook to GitHub for non-prod Jenkins') {
        loggingUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO EACH GIT REPO")

        projectInfo.microServices.each { microService ->
            githubUtils.pushBuildWebhook(projectInfo, microService, 'build-to-dev')
        }

        projectInfo.libraries.each { library ->
            githubUtils.pushBuildWebhook(projectInfo, library, 'build-library')
        }
    }
}