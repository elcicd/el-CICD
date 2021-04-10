/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete a project from OKD; i.e. the opposite of onboarding a project.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage('Remove project namespace environments') {
        def namespacesToDelete = []
        if (args.isNonProd) {
            namespacesToDelete.addAll(projectInfo.nonProdNamespaces)
            namespacesToDelete.addAll(projectInfo.sandboxNamespaces)
            projectInfo.allowsHotfixes && namespacesToDelete.add(projectInfo.hotfixNamespace)
        }
        else {
            namespacesToDelete.add(projectInfo.prodNamespace)
        }

        namespacesToDelete += args.deleteRbacGroupJenkins ? " ${projectInfo.cicdMasterNamespace}" : ''

        def msg = args.deleteRbacGroupJenkins ?
            "REMOVING ${projectInfo.rbacGroup} AUTOMATION SERVER AND ${projectInfo.id} ENVIRONMENT(S):" :
            "REMOVING ${projectInfo.id} NON-PROD ENVIRONMENT(S):"

        pipelineUtils.echoBanner(msg, namespacesToDelete)

        onboardingUtils.deleteNamespaces(namespacesToDelete)
    }

    stage('Delete GitHub deploy keys') {
        credentialUtils.deleteDeployKeysFromGithub(projectInfo)

        if (!args.deleteRbacGroupJenkins) {
            credentialUtils.deleteDeployKeysFromJenkins(projectInfo)
        }
    }

    if (args.isNonProd) {
        stage('Remove project build-to-dev pipelines from Jenkins') {
            if (!args.deleteRbacGroupJenkins) {
                onboardingUtils.cleanStalePipelines(projectInfo)
            }
            else {
                pipelineUtils.echoBanner("DELETED ${projectInfo.cicdMasterNamespace} NAMESPACE: BUILD-TO-DEV PIPELINES ALREADY DELETED")
            }
        }
    }
}