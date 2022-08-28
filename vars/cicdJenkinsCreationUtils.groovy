/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for bootstrapping CICD non-prod and prod environments
 * Should be called in order as written
 */

def verifyCicdJenkinsExists(def projectInfo, def isNonProd) {
    stage("Check if group's prod or non-prod CICD Jenkins exist") {
        jenkinsUtils.configureCicdJenkinsUrls(projectInfo)
        
        def prodOrNonProd  = "${isNonProd ? 'NON-' : ''}PROD"
        loggingUtils.echoBanner("VERIFY ${projectInfo.rbacGroup}'S ${prodOrNonProd} CICD JENKINS EXIST")

        sh """
            echo 'Verify group '${projectInfo.rbacGroup}' exists'
            oc get groups ${projectInfo.rbacGroup} --no-headers
        """

        def cicdMasterProjectExist =
            sh(returnStdout: true, script: "oc get rc --ignore-not-found -l app=jenkins-persistent -n ${projectInfo.cicdMasterNamespace}")

        if (!cicdMasterProjectExist) {
            onboardingUtils.deleteNamespaces(projectInfo.cicdMasterNamespace)

            def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
            createCicdNamespaceAndJenkins(projectInfo, envs)
        }
        else {
            echo "EXISTENCE CONFIRMED: ${prodOrNonProd} CICD JENKINS EXIST"
        }
    }
}

def createCicdNamespaceAndJenkins(def projectInfo, def envs) {
    stage('Creating CICD namespaces and rbacGroup Jenkins') {
        def nodeSelectors = el.cicd.CICD_MASTER_NODE_SELECTORS ? "--node-selector='${el.cicd.CICD_MASTER_NODE_SELECTORS }'" : ''

        sh """
            ${loggingUtils.shellEchoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

            oc adm new-project ${projectInfo.cicdMasterNamespace} ${nodeSelectors}
    
            helm upgrade --install --history-max=0 --cleanup-on-fail  \
                --set elCicdChart.parameters.JENKINS_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME} \
                --set elCicdChart.parameters.JENKINS_URL=${JENKINS_URL} \
                --set "elCicdChart.parameters.OPENSHIFT_ENABLE_OAUTH='${JENKINS_OPENSHIFT_ENABLE_OAUTH}'" \
                --set elCicdChart.parameters.CPU_LIMIT=${JENKINS_CPU_LIMIT} \
                --set elCicdChart.parameters.MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
                --set elCicdChart.parameters.VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
                --set elCicdChart.parameters.JENKINS_IMAGE_PULL_SECRET=${JENKINS_IMAGE_PULL_SECRET} \
                -n ${ONBOARDING_MASTER_NAMESPACE} \
                -f ${CONFIG_REPOSITORY_JENKINS_HELM}/values.yml \
                jenkins \
                ${CONFIG_REPOSITORY_JENKINS_HELM}

            ${shCmd.echo ''}
            oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                          -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                                          -p DISABLE_ADMINISTRATIVE_MONITORS=${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                          -p JENKINS_IMAGE_STREAM_TAG=${el.cicd.JENKINS_IMAGE_NAME}:latest \
                                          -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                          -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                          -e TRY_UPGRADE_IF_NO_MARKER=true \
                                          -e CASC_JENKINS_CONFIG=${el.cicd.JENKINS_CONTAINER_CONFIG_DIR}/${el.cicd.JENKINS_CASC_FILE} \
                                          -n ${projectInfo.cicdMasterNamespace}
            ${shCmd.echo ''}
            ${shCmd.echo 'Creating nonrootbuilder SCC if necessary and applying to jenkins ServiceAccount'}
            oc apply -f ${el.cicd.JENKINS_CONFIG_DIR}/jenkinsServiceAccountSecurityContext.yml
            oc adm policy add-scc-to-user nonroot-builder -z jenkins -n ${projectInfo.cicdMasterNamespace}

            ${shCmd.echo ''}
            ${shCmd.echo 'Adding edit privileges for the rbacGroup to their CICD Automation Namespace'}
            oc policy add-role-to-group edit ${projectInfo.rbacGroup} -n ${projectInfo.cicdMasterNamespace}

            ${shCmd.echo ''}
            sleep 2
            ${shCmd.echo 'Waiting for Jenkins to come up...'}
            oc rollout status dc jenkins -n ${projectInfo.cicdMasterNamespace}
            ${shCmd.echo ''}
            ${shCmd.echo 'Jenkins up, sleep for 5 more seconds to make sure server REST api is ready'}
            sleep 5
        """
    }
}
