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

def createNamepace(def projectInfo, def namespace, def env, def nodeSelectors) {
    sh """
        if [[ -z \$(oc get projects --ignore-not-found ${namespace}) ]]
        then
            if [[ ! -z ${nodeSelectors} ]]
            then
                oc adm new-project ${namespace} --node-selector='${nodeSelectors}'
            else
                oc new-project ${namespace}
            fi

            oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${namespace}

            oc policy add-role-to-user edit system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}

            oc adm policy add-cluster-role-to-user sealed-secrets-management \
                system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}
            oc adm policy add-cluster-role-to-user secrets-unsealer \
                system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${namespace}

            ${shellEcho ''}
        fi
    """
}

def createNamepaces(def projectInfo, def namespaces, def environments, def nodeSelectors) {
    pipelineUtils.echoBanner("SETUP NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}:", namespaces.join(', '))

    sh """
        NODE_SELECTORS=(${nodeSelectors.join(' ') ?: ''})
        ENVS=(${environments.join(' ')})
        NAMESPACES=(${namespaces.join(' ')})
        for i in \${!NAMESPACES[@]}
        do
            if [[ -z \$(oc get projects --ignore-not-found \${NAMESPACES[\${i}]}) ]]
            then
                if [[ ! -z \${NODE_SELECTORS[\${i}]} ]]
                then
                    oc adm new-project \${NAMESPACES[\${i}]} --node-selector="\${NODE_SELECTORS[\${i}]}"
                else
                    oc new-project \${NAMESPACES[\${i}]}
                fi

                oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n \${NAMESPACES[\${i}]}

                oc policy add-role-to-user edit system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n \${NAMESPACES[\${i}]}

                oc adm policy add-cluster-role-to-user sealed-secrets-management \
                    system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n \${NAMESPACES[\${i}]}
                oc adm policy add-cluster-role-to-user secrets-unsealer \
                    system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n \${NAMESPACES[\${i}]}

                ${shellEcho ''}
            fi
        done
    """
}

def applyResoureQuota(def projectInfo, def namespace, def resourceQuotaFile) {
    sh "oc delete quota -l=projectid=${projectInfo.id} -n ${namespace}"

    if (resourceQuotaFile) {
        sh """
            sleep 3

            oc apply -f ${resourceQuotaFile} -n ${namespace}
            oc label projectid=${projectInfo.id} -f ${resourceQuotaFile} -n ${namespace}

            ${shellEcho ''}
        """
    }
}

def createNfsPersistentVolumes(def projectInfo, def isNonProd) {
    // def pvNames = [:]
    // if (projectInfo.nfsShares) {
    //     pipelineUtils.echoBanner("SETUP NFS PERSISTENT VOLUMES:", projectInfo.nfsShares.collect { it.claimName }.join(', '))

    //     dir(el.cicd.OKD_TEMPLATES_DIR) {
    //         projectInfo.nfsShares.each { nfsShare ->
    //             def envs = isNonProd ? projectInfo.nonProdEnvs : [projectInfo.prodEnv]
    //             envs.each { env ->
    //                 pvName = "${projectInfo.id}-${env}-${nfsShare.claimName}"
    //                 pvNames[(pvName)] = true
    //                 if ((isNonProd && env != projectInfo.prodEnv) || (!isNonProd && env == projectInfo.prodEnv)) {
    //                     createNfsShare(projectInfo, nfsShare, pvName, env)
    //                 }
    //             }
    //         }
    //     }
    // }

    // pipelineUtils.echoBanner("REMOVE UNUSED, RELEASED NFS PERSISTENT VOLUMES, IF ANY")
    // def releasedPvs = sh(returnStdout: true, script: """
    //     oc get pv -l projectid=${projectInfo.id} --ignore-not-found | grep 'Released' | awk '{ print \$1 }'
    // """).split('\n').findAll { it.trim() }

    // releasedPvs.each { pvName ->
    //     if (!pvNames[(pvName)]) {
    //         sh "oc delete pv ${pvName}"
    //     }
    // }
}

def createNfsShare(def projectInfo, def nfsShare, def nfsShareName, def env) {
    sh """
        oc process --local \
                -f nfs-pv-template.yml \
                -l projectid=${projectInfo.id}\
                -p PV_NAME=${nfsShareName} \
                -p CAPACITY=${nfsShare.capacity} \
                -p ACCESS_MODE=${nfsShare.accessMode} \
                -p NFS_EXPORT=${nfsShare.exportPath} \
                -p NFS_SERVER=${nfsShare.server} \
                -p CLAIM_NAME=${nfsShare.claimName} \
                -p NAMESPACE=${projectInfo.id}-${env} \
            | oc apply -f -

        ${shellEcho ''}
    """
}
