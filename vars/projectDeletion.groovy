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

            COUNTER=1
            until
                -z \$(oc get projects --no-headers --ignore-not-found ${namespacesToDelete})
            do
                printf "%0.s-" \$(seq 1 \${COUNTER})
                echo
                sleep 3
                let COUNTER+=1
            done
        """
    }

    stage('Delete GitHub deploy keys') {
        credentialUtils.deleteDeployKeysFromGithub(projectInfo)

        if (!args.deleteRbacGroupJenkins) {
            credentialUtils.deleteDeployKeysFromJenkins(projectInfo)
        }
    }

    if (!args.deleteRbacGroupJenkins) {
        stage('Remove project build-to-dev pipelines from Jenkins') {
            def namespacesToDelete = projectInfo.nonProdNamespaces.values().join(' ')
            namespacesToDelete += projectInfo.sandboxNamespaces ? ' ' + projectInfo.sandboxNamespaces.join(' ') : ''
            namespacesToDelete += args.deleteRbacGroupJenkins ? " ${projectInfo.cicdMasterNamespace}" : ''

            sh """
                ${pipelineUtils.shellEchoBanner("REMOVING PROJECT BUILD-TO-DEV PIPELINES FOR ${projectInfo.id}")}

                for BCS in \$(oc get bc -l projectid=${projectInfo.id} -n ${projectInfo.cicdMasterNamespace} -o jsonpath='{.items[*].metadata.name}')
                do
                    while [ \$(oc get bc \${BCS} -n ${projectInfo.cicdMasterNamespace} | grep \${BCS} | wc -l) -gt 0 ] ;
                    do
                        oc delete bc \${BCS} --ignore-not-found -n ${projectInfo.cicdMasterNamespace}
                        sleep 3
                        ${shellEcho '-'}
                    done
                done
            """
        }
    }
}