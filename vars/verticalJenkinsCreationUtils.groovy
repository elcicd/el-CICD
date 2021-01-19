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

        def cicdNamespace = isNonProd ? projectInfo.nonProdCicdNamespace : projectInfo.prodCicdNamespace

        def cicdProjectsExist = sh(returnStdout: true, script: "oc get projects --ignore-not-found ${cicdNamespace}")

        if (!cicdProjectsExist) {
            stage('Creating CICD namespaces and rbacGroup Jenkins') {
                def envs = isNonProd ? projectInfo.nonProdEnvs : [projectInfo.prodEnv]

                createCicdNamespaceAndJenkins(projectInfo, cicdNamespace, envs)
                waitUntilJenkinsIsReady(cicdNamespace)
            }

            def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
            credentialsUtils.pushElCicdCredentialsToCicdServer(cicdNamespace, envs)
        }
        else {
            echo "EXISTENCE CONFIRMED: ${prodOrNonProd} CICD JENKINS EXIST"
        }

        def pipelines = isNonProd ? el.getNonProdPipelines() : el.getProdPipelines()
        refreshSharedPipelines(projectInfo, cicdJenkinsNamespace, pipelines)
    }
}

def refreshSharedPipelines(def projectInfo, def cicdJenkinsNamespace, def pipelines) {
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
                    oc process -f \${FILE} -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} -n ${cicdJenkinsNamespace} | \
                        oc apply -f - -n ${cicdJenkinsNamespace}
                done
            """
        }
    }
}

def createCicdNamespaceAndJenkins(def projectInfo, def cicdJenkinsNamespace, def envs) {
    sh """
        ${pipelineUtils.shellEchoBanner("CREATING ${cicdJenkinsNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

        oc adm new-project ${cicdJenkinsNamespace} --node-selector='${el.cicd.EL_CICD_RBAC_GROUP_MASTER_NODE_SELECTORS}'

        oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                      -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                                      -p DISABLE_ADMINISTRATIVE_MONITORS=${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                      -p JENKINS_IMAGE_STREAM_TAG=${el.cicd.JENKINS_IMAGE_STREAM}:latest \
                                      -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                      -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                      -e TRY_UPGRADE_IF_NO_MARKER=true \
                                      -e CASC_JENKINS_CONFIG=${el.cicd.EL_CICD_JENKINS_CONTAINER_CONFIG_DIR}/${el.cicd.JENKINS_CASC_FILE} \
                                      -n ${cicdJenkinsNamespace}

        oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND}| oc create -f - -n ${cicdJenkinsNamespace}

        for ENV in ${envs.join(' ')}
        do
            oc get secrets \${ENV}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX} -o yaml -n ${el.cicd.EL_CICD_MASTER_NAMESPACE} | \
                ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | \
                oc apply -f - -n ${cicdJenkinsNamespace}
        done

        oc policy add-role-to-group admin ${projectInfo.rbacGroup} -n ${cicdJenkinsNamespace}
    """
}

def waitUntilJenkinsIsReady(def cicdNamespace) {
    sh """
        ${pipelineUtils.shellEchoBanner("ENSURE ${cicdNamespace} JENKINS IS READY (CAN TAKE A FEW MINUTES)")}

        set +x
        COUNTER=1
        for PROJECT in ${cicdNamespace}
        do
            until
                oc get pods -l name=jenkins -n \${PROJECT} | grep "1/1"
            do
                printf "%0.s-" \$(seq 1 \${COUNTER})
                echo
                sleep 5
                let COUNTER+=1
            done
        done

        echo "Jenkins up, sleep for 10 more seconds to make sure each servers REST api are ready"
        sleep 10
        set -x
    """
}
