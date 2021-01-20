/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the CICD framework
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def init() {
    pipelineUtils.echoBanner("COPYING ONBOARDING RESOURCES TO JENKINS AGENT")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json", text: libraryResource('templates/githubSshCredentials-postfix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json", text: libraryResource('templates/githubSshCredentials-prefix.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml", text: libraryResource('templates/jenkinsSshCredentials-postfix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml", text: libraryResource('templates/jenkinsSshCredentials-prefix.xml')
    writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml", text: libraryResource('templates/jenkinsTokenCredentials-template.xml')
}

def createNamepaces(def projectInfo, def namespaces, def environments, def nodeSelectors) {
    pipelineUtils.echoBanner("SETUP OPENSHIFT NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", namespaces.join(', '))

    sh """
        NODE_SELECTORS=(${nodeSelectors.join(' ')})
        ENVS=(${environments.join('  ')})
        NAMESPACES=(${namespaces.join(' ')})
        for i in \${!NAMESPACES[@]}
        do
            if [[ `oc projects | grep \${NAMESPACES[\$i]} | wc -l` -lt 1 ]]
            then
                if [[ \${NODE_SELECTORS[\$i]} != 'null' ]]
                then
                    oc adm new-project \${NAMESPACES[\$i]} --node-selector="\${NODE_SELECTORS[\$i]}"
                else
                    oc adm new-project \${NAMESPACES[\$i]}
                fi

                oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n \${NAMESPACES[\$i]}

                oc policy add-role-to-user edit system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n \${NAMESPACES[\$i]}

                oc adm policy add-cluster-role-to-user sealed-secrets-management \
                    system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n \${NAMESPACES[\$i]}
                oc adm policy add-cluster-role-to-user secrets-unsealer \
                    system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n \${NAMESPACES[\$i]}

                oc get secrets -l \${ENVS[\$i]}-env -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                    oc create -f - -n \${NAMESPACES[\$i]}

                ${shellEcho ''}
            fi
        done
    """
}
