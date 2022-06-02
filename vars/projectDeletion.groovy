/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete a project from OKD; i.e. the opposite of onboarding a project.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage('Remove project namespace environments') {
        def namespacesToDelete = []
        namespacesToDelete.addAll(projectInfo.nonProdNamespaces.values())
        namespacesToDelete.addAll(projectInfo.sandboxNamespaces.values())
        projectInfo.allowsHotfixes && namespacesToDelete.add(projectInfo.hotfixNamespace)

        namespacesToDelete += args.deleteJenkinsCicdServer ? " ${projectInfo.cicdMasterNamespace}" : ''

        def msg = args.deleteJenkinsCicdServer ?
            "REMOVING ${projectInfo.rbacGroup} AUTOMATION SERVER AND ${projectInfo.id} ENVIRONMENT(S):" :
            "REMOVING ${projectInfo.id} ENVIRONMENT(S):"

        loggingUtils.echoBanner(msg, namespacesToDelete.join(' '))

        onboardingUtils.deleteNamespaces(namespacesToDelete)
    }

    stage('Delete GitHub deploy keys') {
        githubUtils.deleteProjectDeployKeys(projectInfo)

        if (!args.deleteJenkinsCicdServer) {
            jenkinsUtils.deleteProjectDeployKeysFromJenkins(projectInfo)
        }
    }

    stage('Remove project build-to-dev pipelines from Jenkins') {
        if (!args.deleteJenkinsCicdServer) {
            onboardingUtils.cleanStaleBuildToDevPipelines(projectInfo)
        }
        else {
            loggingUtils.echoBanner("DELETED ${projectInfo.cicdMasterNamespace} NAMESPACE: BUILD-TO-DEV PIPELINES ALREADY DELETED")
        }
    }
}