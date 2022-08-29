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

        createCicdNamespaceAndJenkins(projectInfo)
    }
}

def createCicdNamespaceAndJenkins(def projectInfo) {
    stage('Creating CICD namespace and rbacGroup Jenkins Automation Server') {
        def nodeSelectors = el.cicd.CICD_MASTER_NODE_SELECTORS ? "--node-selector='${el.cicd.CICD_MASTER_NODE_SELECTORS }'" : ''
        
        if (!el.cicd.JENKINS_IMAGE_PULL_SECRET && el.cicd.OKD_VERSION) {
            el.cicd.JENKINS_IMAGE_PULL_SECRET =
                sh(returnStdout: true,
                    script: """
                        oc get secrets -o custom-columns=:metadata.name -n ${projectInfo.cicdMasterNamespace} | \
                        grep deployer-dockercfg | \
                        tr -d '[:space:]'
                    """
                )
        }
        
        def jenkinsUrl = "jenkins-${projectInfo.cicdMasterNamespace}"
        sh """
            ${loggingUtils.shellEchoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

            if [[ -z \$(oc get project --ignore-not-found ${projectInfo.cicdMasterNamespace}) ]]
            then
                oc adm new-project ${projectInfo.cicdMasterNamespace} ${nodeSelectors}
            fi
    
            ${shCmd.echo ''}
            helm dependency update ${el.cicd.JENKINS_HELM_DIR}
            
            ${shCmd.echo ''}
            helm upgrade --install --history-max=0 --cleanup-on-fail --debug \
                --set elCicdChart.parameters.JENKINS_IMAGE=${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_IMAGE_NAME} \
                --set elCicdChart.parameters.JENKINS_URL=${jenkinsUrl} \
                --set 'elCicdChart.parameters.OPENSHIFT_ENABLE_OAUTH="${el.cicd.OKD_VERSION ? true : false}"' \
                --set elCicdChart.parameters.CPU_LIMIT=${el.cicd.JENKINS_CPU_LIMIT} \
                --set elCicdChart.parameters.MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                --set elCicdChart.parameters.VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                --set elCicdChart.parameters.JENKINS_IMAGE_PULL_SECRET=${el.cicd.JENKINS_IMAGE_PULL_SECRET} \
                -n ${projectInfo.cicdMasterNamespace} \
                -f ${el.cicd.JENKINS_HELM_DIR}/values.yml \
                jenkins \
                ${el.cicd.JENKINS_HELM_DIR}

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
            oc rollout status deploy jenkins -n ${projectInfo.cicdMasterNamespace}
            ${shCmd.echo ''}
            ${shCmd.echo 'Jenkins up, sleep for 5 more seconds to make sure server REST api is ready'}
            sleep 5
        """
    }
}
