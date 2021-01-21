/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the project onboarding pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/project-onboarding-pipeline-template
 *
 */

def call(Map args) {
    onboardingUtils.init()

    def projectInfo = args.projectInfo
    projectInfo.rbacGroup = params.RBAC_GROUP ?: projectInfo.rbacGroup

    verticalJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, false)

    stage('Remove stale namespace environments, if necessary') {
        if (args.recreateProd) {
            sh """
                oc delete project ${projectInfo.prodNamespace} || true
                until
                    !(oc project  ${projectInfo.prodNamespace} > /dev/null 2>&1)
                do
                    sleep 1
                done
            """
        }
    }

    stage('Setup prod openshift namespace environment') {
        def nodeSelectors = el.cicd["${projectInfo.PROD_ENV}${el.cicd.NODE_SELECTORS_POSTFIX}"]

        sh """
            ${pipelineUtils.shellEchoBanner("SETUP OPENSHIFT PROD NAMESPACE ENVIRONMENT AND JENKINS RBAC FOR ${projectInfo.id}")}

            if [[ `oc projects | grep ${projectInfo.prodNamespace} | wc -l` -lt 1 ]]
            then
                __NODE_SELS=${nodeSelectors ?: ''}
                if [[ ! -z \${__NODE_SELS} ]]
                then
                    oc adm new-project ${projectInfo.prodNamespace} --node-selector="${nodeSelectors}"
                else
                    oc adm new-project ${projectInfo.prodNamespace}
                fi

                oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${projectInfo.prodNamespace}

                oc policy add-role-to-user edit system:serviceaccount:${projectInfo.}:jenkins -n ${projectInfo.prodNamespace}

                oc adm policy add-cluster-role-to-user sealed-secrets-management system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${projectInfo.prodNamespace}
                oc adm policy add-cluster-role-to-user secrets-unsealer system:serviceaccount:${projectInfo.cicdMasterNamespace}:jenkins -n ${projectInfo.prodNamespace}

                oc get secrets -l ${projectInfo.prodEnv}-env=true -o yaml -n ${el.cicd.EL_CICD_PROD_MASTER_NAMEPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | oc create -f - -n ${projectInfo.prodNamespace}
            fi
        """
    }

    stage('Delete old github public keys with curl') {
        credentialsUtils.deleteDeployKeysFromGithub(projectInfo)
    }

    stage('Create and push public key for each github repo to github with curl') {
        credentialsUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo)
    }
}