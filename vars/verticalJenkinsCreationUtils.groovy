/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for bootstrapping CICD non-prod and prod environments
 * Should be called in order as written
 */

def verifyCicdJenkinsExists(def projectInfo, def isNonProd) {
    stage("Check if group's prod or non-prod CICD Jenkins exist") {
        def prodOrNonProd  = "${isNonProd ? 'NON-' : ''}PROD"
        pipelineUtils.echoBanner("VERIFY ${projectInfo.rbacGroup}'S ${prodOrNonProd} CICD JENKINS EXIST")

        def cicdProjectsExist = sh(returnStdout: true, script: "oc get projects --ignore-not-found ${projectInfo.cicdMasterNamespace}")

        if (!cicdProjectsExist) {
            stage('Creating CICD namespaces and rbacGroup Jenkins') {
                def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PROD_ENV]

                createCicdNamespaceAndJenkins(projectInfo, envs)
                waitUntilJenkinsIsReady(projectInfo)
            }

            stage('Push Image Repo Pull Secrets to rbacGroup Jenkins') {
                def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
                credentialsUtils.pushElCicdCredentialsToCicdServer(projectInfo, envs)
            }
        }
        else {
            echo "EXISTENCE CONFIRMED: ${prodOrNonProd} CICD JENKINS EXIST"
        }

        def pipelines = isNonProd ? el.getNonProdPipelines() : el.getProdPipelines()
        refreshSharedPipelines(projectInfo, pipelines)
    }
}

def createCicdNamespaceAndJenkins(def projectInfo, def envs) {
    def secretNames = envs.collect { el.cicd["${it}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"] }.toSet()

    sh """
        ${pipelineUtils.shellEchoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

        oc adm new-project ${projectInfo.cicdMasterNamespace} --node-selector='${el.cicd.EL_CICD_RBAC_GROUP_MASTER_NODE_SELECTORS}'

        oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                      -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                                      -p DISABLE_ADMINISTRATIVE_MONITORS=${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                      -p JENKINS_IMAGE_STREAM_TAG=${el.cicd.JENKINS_IMAGE_STREAM}:latest \
                                      -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                      -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                      -e TRY_UPGRADE_IF_NO_MARKER=true \
                                      -e CASC_JENKINS_CONFIG=${el.cicd.EL_CICD_JENKINS_CONTAINER_CONFIG_DIR}/${el.cicd.JENKINS_CASC_FILE} \
                                      -n ${projectInfo.cicdMasterNamespace}

        oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | \
            ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
            oc create -f - -n ${projectInfo.cicdMasterNamespace}

        for ENV in ${secretNames.join(' ')}
        do
            oc get secrets \${ENV} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | \
                ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                oc apply -f - -n ${projectInfo.cicdMasterNamespace}
        done

        oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${projectInfo.cicdMasterNamespace}
    """
}

def waitUntilJenkinsIsReady(def projectInfo) {
    sh """
        ${pipelineUtils.shellEchoBanner("ENSURE ${projectInfo.cicdMasterNamespace} JENKINS IS READY (CAN TAKE A FEW MINUTES)")}

        set +x
        COUNTER=1
        for PROJECT in ${projectInfo.cicdMasterNamespace}
        do
            until
                oc get pods -l name=jenkins -n \${PROJECT} | grep "1/1"
            do
                printf "%0.s-" \$(seq 1 \${COUNTER})
                echo
                sleep 3
                let COUNTER+=1
            done
        done

        echo "Jenkins up, sleep for 10 more seconds to make sure each servers REST api are ready"
        sleep 10
        set -x
    """
}

def refreshSharedPipelines(def projectInfo, def pipelines) {
    stage('Refreshing shared pipelines') {
        def msg = ['CREATING SHARED PIPELINES:']
        msg.addAll(pipelines)
        pipelineUtils.echoBanner(msg)

        pipelines.each {
            writeFile file:"${el.cicd.BUILDCONFIGS_DIR}/${it}", text: libraryResource("buildconfigs/${it}")
        }

        dir (el.cicd.BUILDCONFIGS_DIR) {
            sh """
                for FILE in ${pipelines.join(' ')}
                do
                    oc process -f \${FILE} -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} -n ${projectInfo.cicdMasterNamespace} | \
                        oc apply -f - -n ${projectInfo.cicdMasterNamespace}
                done
            """
        }
    }
}
