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

                oc get secrets -l \${ENVS[\${i}]}-env -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                    oc create -f - -n \${NAMESPACES[\${i}]}

                ${shellEcho ''}
            fi
        done
    """
}

def createResourceQuotas(def projectInfo, def isNonProd) {
    def resQuotaNames = [:]
    def envs = isNonProd ? projectInfo.nonProdEnvs : [projectInfo.prodEnv]
    envs.each {
        sh "oc delete quota -l=projectid=${it}"
    }

    dir(el.cicd.OKD_TEMPLATES_DIR) {
        projectInfo?.resourceQuotas.each { resourceQuota ->
            envs.each { env ->
                if (resourceQuota[(env)]) {
                    sh """
                        oc delete quota -l=projectid=${projectInfo.id}
                        oc create --l=projectid=${projectInfo.id} -f ${resourceQuota[(env)]} -n ${projectInfo.id}-${env}
                        ${shellEcho ''}
                    """
                }
            }
        }
    }
}

def createNfsPersistentVolumes(def projectInfo, def isNonProd) {
    def pvNames = [:]
    dir(el.cicd.OKD_TEMPLATES_DIR) {
        projectInfo?.nfsShares.each { nfsShare ->
            def envs = isNonProd ?
                nfsShare.envs.collect { it != projectInfo.prodEnv } :
                nfsShare.envs.collect { it == projectInfo.prodEnv }
            envs.each { env ->
                pvName = "nfs-${projectInfo.id}-${env}-${nfsShare.claimName}"
                pvNames[(pvName)] = true
                if ((isNonProd && env != projectInfo.prodEnv) || (!isNonProd && env = projectInfo.prodEnv))
                    createNfsShare(projectInfo, nfsShare, pvName, env)
                }
            }
        }
    }

    def releasedPvs = sh(returnStdout: true, sh """
        oc get pv -l projectid=${projectInfo.id} | grep 'Released' | awk '{ print $1 }'
    """).splt('\n')

    releasedPvs.each { pvName ->
        if (!pvNames[(pvName)]) {
            sh "oc delete pv ${pvName}"
        }
    }
}

def createNfsShare(def projectInfo, def nfsShare, def nfsShareName, def env) {
    sh """
        oc process --local \
                -f nfs-pv-template.yml \
                -p PV_NAME=nfs-${nfsShareName} \
                -p CAPACITY=${nfsShare.capacity} \
                -p ACCESS_MODE=${nfsShare.accessMode} \
                -p RECLAIM_POLICY=${nfsShare.reclaimPolicy} \
                -p NFS_EXPORT=${nfsShare.nfsExportPath} \
                -p NFS_SERVER=${nfsShare.nfsServer} \
                -p CLAIM_NAME=${nfsShare.claimName} \
                -p NAMESPACE=${projectInfo.id}-${env} \
            | oc create --label=projectid=${projectInfo.id} -f -

        ${shellEcho ''}
    """
}
