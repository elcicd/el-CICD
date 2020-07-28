/*
 * Defines the bulk of the project onboarding pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/project-onboarding-pipeline-template
 *
 */

def call(Map args) {

    elCicdCommons.initialize()

    elCicdCommons.cloneElCicdRepo()

    def projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)

    def cicdRbacGroupJenkinsCredsUrls = verticalJenkinsCreationUtils.buildCicdJenkinsUrls(projectInfo)

    verticalJenkinsCreationUtils.verifyCicdJenkinsExists(projectInfo, cicdRbacGroupJenkinsCredsUrls, true)

    stage('Remove stale namespace environments and pipelines if necessary') {
        def namespacesToDelete = args.rebuildNonProd ? projectInfo.nonProdNamespaces.values().join(' ') : ''

        sh """
            ${pipelineUtils.shellEchoBanner("REMOVING STALE PIPELINES FOR ${projectInfo.id}, IF ANY")}

            for BCS in `oc get bc -l projectid=${projectInfo.id} -n ${projectInfo.nonProdCicdNamespace} | grep Jenkins | awk '{print \$1}'`
            do
                while [ `oc get bc \${BCS} -n ${projectInfo.nonProdCicdNamespace} | grep \${BCS} | wc -l` -gt 0 ] ;
                do
                    oc delete bc \${BCS} --ignore-not-found -n ${projectInfo.nonProdCicdNamespace}
                    sleep 5
                    ${shellEcho ''}
                done
            done

            ${ args.rebuildNonProd ? pipelineUtils.shellEchoBanner("REMOVING STALE NON-PROD ENVIRONMENT(S) FOR ${projectInfo.id}") : ''}

            ${args.rebuildNonProd ? "oc delete project ${namespacesToDelete} || true" : ''}

            NAMESPACES_TO_DELETE='${namespacesToDelete}'
            for NAMESPACE in \${NAMESPACES_TO_DELETE}
            do
                until
                    !(oc project \${NAMESPACE} > /dev/null 2>&1)
                do
                    sleep 1
                done
            done
        """
    }

    stage('Add build-to-dev pipeline for each Github repo on non-prod Jenkins') {
        pipelineUtils.echoBanner("ADD BUILD AND DEPLOY PIPELINE FOR EACH GITHUB REPO ON NON-PROD JENKINS FOR ${projectInfo.id}")

        dir ("${el.cicd.EL_CICD_DIR}/buildconfigs") {
            projectInfo.microServices.each { microService ->
                sh """
                    oc process --local \
                               -f build-to-dev-pipeline-template.yml \
                               -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} \
                               -p PROJECT_ID=${projectInfo.id} \
                               -p MICROSERVICE_GIT_REPO=${microService.gitRepoUrl} \
                               -p MICROSERVICE_NAME=${microService.name} \
                               -p GIT_BRANCH=${projectInfo.gitBranch} \
                               -p CODE_BASE=${microService.codeBase} \
                        | oc create -f - -n ${projectInfo.nonProdCicdNamespace}

                    ${shellEcho ''}
                """
            }
        }
    }

    stage('Setup openshift namespace environments') {
        def nodeSelectors = projectInfo.NON_PROD_ENVS.collect { ENV ->
            return el.cicd["${ENV}_NODE_SELECTORS"]?.replaceAll(/\s/, '') ?: 'null'
        }.join(' ')

        sh """
            ${pipelineUtils.shellEchoBanner("SETUP OPENSHIFT NAMESPACE ENVIRONMENTS AND JENKINS RBAC FOR ${projectInfo.id}")}

            NODE_SELECTORS=(${nodeSelectors})
            ENVS=(${projectInfo.nonProdNamespaces.keySet().join('  ')})
            NAMESPACES=(${projectInfo.nonProdNamespaces.values().join(' ')})
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

                    oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${el.cicd.EL_CICD_NON_PROD_MASTER_NAMEPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND}| oc create -f - -n \${NAMESPACES[\$i]}

                    oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n \${NAMESPACES[\$i]}

                    oc policy add-role-to-user edit system:serviceaccount:${projectInfo.nonProdCicdNamespace}:jenkins -n \${NAMESPACES[\$i]}

                    oc adm policy add-cluster-role-to-user sealed-secrets-management system:serviceaccount:${projectInfo.nonProdCicdNamespace}:jenkins -n \${NAMESPACES[\$i]}
                    oc adm policy add-cluster-role-to-user secrets-unsealer system:serviceaccount:${projectInfo.nonProdCicdNamespace}:jenkins -n \${NAMESPACES[\$i]}

                    oc get sealedsecrets -l \${ENVS[\$i]}-env=true -o yaml -n ${el.cicd.EL_CICD_NON_PROD_MASTER_NAMEPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | oc create -f - -n \${NAMESPACES[\$i]}

                    ${shellEcho ''}
                fi
            done
        """
    }

    stage('Delete old github public keys with curl') {
        onboardingUtils.deleteOldGithubKeys(projectInfo, true)
    }

    stage('Create and push public key for each github repo to github with curl') {
        onboardingUtils.createAndPushPublicPrivateGithubRepoKeys(projectInfo, cicdRbacGroupJenkinsCredsUrls, true)
    }

    stage('Push Webhook to GitHub for non-prod Jenkins') {
        pipelineUtils.echoBanner("PUSH ${projectInfo.id} NON-PROD JENKINS WEBHOOK TO GITHUB FOR EACH REPO")

        withCredentials([string(credentialsId: el.cicd.GIT_SITE_WIDE_ACCESS_TOKEN_ID, variable: 'GITHUB_ACCESS_TOKEN')]) {
            projectInfo.microServices.each { microService ->
                scriptToPushWebhookToScm =
                    scmScriptHelper.getScriptToPushWebhookToScm(projectInfo, microService, GITHUB_ACCESS_TOKEN)
                sh """
                    ${shellEcho  "GIT REPO NAME: ${microService.gitRepoName}"}

                    ${scriptToPushWebhookToScm}
                """
            }
        }
    }
}