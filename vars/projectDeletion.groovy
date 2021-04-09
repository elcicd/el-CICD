/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Delete a project from OKD; i.e. the opposite of onboarding a project.
 */

def call(Map args) {
    def projectInfo = args.projectInfo

    stage('Remove project namespace environments') {
        def namespacesToDelete = args.isNonProd ? projectInfo.nonProdNamespaces.values().join(' ') : projectInfo.prodNamespace
        if (args.isNonProd) {
            namespacesToDelete += projectInfo.sandboxNamespaces ? ' ' + projectInfo.sandboxNamespaces.join(' ') : ''
            namespacesToDelete += projectInfo.allowsHotfixes ? ' ' + projectInfo.hotfixNamespace : ''
        }

        namespacesToDelete += args.deleteRbacGroupJenkins ? " ${projectInfo.cicdMasterNamespace}" : ''

        def msg = args.deleteRbacGroupJenkins ?
            "REMOVING ${projectInfo.rbacGroup} AUTOMATION SERVER AND ${projectInfo.id} ENVIRONMENT(S):" :
            "REMOVING ${projectInfo.id} NON-PROD ENVIRONMENT(S):"

        sh """
            ${pipelineUtils.shellEchoBanner(msg, namespacesToDelete)}

            oc delete project --ignore-not-found ${namespacesToDelete}

            set +x
            COUNTER=1
            until [[ -z \$(oc get projects --no-headers --ignore-not-found ${namespacesToDelete}) ]]
            do
                printf "%0.s-" \$(seq 1 \${COUNTER})
                echo
                sleep 3
                let COUNTER+=1
            done
            set -x
        """
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