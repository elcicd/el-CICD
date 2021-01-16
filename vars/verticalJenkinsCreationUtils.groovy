/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for bootstrapping CICD non-prod and prod environments
 * Should be called in order as written
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

def buildCicdJenkinsUrls(def projectInfo) {
    def protocol = "https"
    def app = "jenkins"
    def createRelativePath = 'credentials/store/system/domain/_/createCredentials'
    def updateRelativePath = 'credentials/store/system/domain/_/credential'

    def cicdRbacGroupJenkinsCredsUrls = [:]

    cicdRbacGroupJenkinsCredsUrls.nonProdCicdJenkinsCredsUrl =
        "${protocol}://${app}-${projectInfo.nonProdCicdNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/${createRelativePath}"
    cicdRbacGroupJenkinsCredsUrls.prodCicdJenkinsCredsUrl =
        "${protocol}://${app}-${projectInfo.prodCicdNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/${createRelativePath}"

    cicdRbacGroupJenkinsCredsUrls.updateNonProdCicdJenkinsCredsUrl =
        "${protocol}://${app}-${projectInfo.nonProdCicdNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/${updateRelativePath}"
    cicdRbacGroupJenkinsCredsUrls.updateProdCicdJenkinsCredsUrl =
        "${protocol}://${app}-${projectInfo.prodCicdNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}/${updateRelativePath}"

    return cicdRbacGroupJenkinsCredsUrls
}

def verifyCicdJenkinsExists(def projectInfo, def cicdRbacGroupJenkinsCredsUrls, def isNonProd) {
    stage("Check if group's prod or non-prod CICD Jenkins exist") {
        def prodOrNonProd  = "${isNonProd ? 'NON-' : ''}PROD"
        pipelineUtils.echoBanner("VERIFY ${projectInfo.rbacGroup}'S ${prodOrNonProd} CICD JENKINS EXIST")

        def cicdNamespace = isNonProd ? projectInfo.nonProdCicdNamespace : projectInfo.prodCicdNamespace

        def cicdProjectsExist = sh(returnStdout: true, script: "oc projects -q | egrep '${cicdNamespace}' | tr '\n' ' '")

        def authBearerCommand = """cat ${el.cicd.TEMPLATES_DIR}/AuthBearerHeader-template.txt | sed "s/%TOKEN%/\$(oc whoami -t)/" > ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt"""
        sh """
            ${shellEcho 'Creating header file with auth token'}
            ${maskCommand(authBearerCommand)}
        """

        if (!cicdProjectsExist.contains(cicdNamespace)) {
            stage('Creating CICD namespaces and rbacGroup Jenkins') {
                createCicdNamespaceAndJenkins(projectInfo, cicdNamespace, isNonProd)
                waitUntilJenkinsIsReady(cicdNamespace)
            }

            if (isNonProd) {
                setupNonProdVerticalCicdNamespacesAndJenkins(projectInfo, cicdRbacGroupJenkinsCredsUrls.nonProdCicdJenkinsCredsUrl)
            }
            else {
                setupProdVerticalCicdNamespacesAndJenkins(projectInfo, cicdRbacGroupJenkinsCredsUrls.prodCicdJenkinsCredsUrl)
            }
        }
        else {
            echo "EXISTENCE CONFIRMED: ${prodOrNonProd} CICD JENKINS EXIST"
        }

        refreshSharedPipelines(projectInfo, isNonProd)
    }
}

def refreshSharedPipelines(def projectInfo, def isNonProd) {
    stage('Refreshing shared pipelines') {
        def nonProdPipelines =
            'build-and-deploy-microservices-pipeline-template.yml ' +
            'create-release-candidate-pipeline-template.yml ' +
            'microservice-promotion-removal-pipeline-template.yml ' +
            'microservice-redeploy-removal-pipeline-template.yml ' +
            'redeploy-release-candidate-pipeline-template.yml '
        def templates = isNonProd ? nonProdPipelines : 'deploy-to-production-pipeline-template.yml'
        pipelineUtils.echoBanner('CREATING SHARED PIPELINES:', templates)

        def cicdJenkinsNamespace = isNonProd ? projectInfo.nonProdCicdNamespace : projectInfo.prodCicdNamespace

        templates.split(/\s+/).each {
            writeFile file:"${el.cicd.BUILDCONFIGS_DIR}/${it}", text: libraryResource("buildconfigs/${it}")
        }

        dir (el.cicd.BUILDCONFIGS_DIR) {
            sh """
                for FILE in ${templates}
                do
                    oc process -f \${FILE} -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} | \
                        oc apply -f - -n ${cicdJenkinsNamespace}
                done
            """
        }
    }
}

def setupNonProdVerticalCicdNamespacesAndJenkins(def projectInfo, def nonProdCicdJenkinsCredsUrl) {
    stage('Setting up non-prod Jenkins') {
        pushSshCredentialstToJenkins(projectInfo.nonProdCicdNamespace, nonProdCicdJenkinsCredsUrl, el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID)
        pushSshCredentialstToJenkins(projectInfo.nonProdCicdNamespace, nonProdCicdJenkinsCredsUrl, el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID)

        def ids = []
        projectInfo.NON_PROD_ENVS.each { ENV ->
            if (!ids.contains(ENV)) {
                pushImageRepositoryTokenToJenkins(projectInfo.nonProdCicdNamespace, el.cicd["${ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], nonProdCicdJenkinsCredsUrl)
                ids += ENV
            }
        }
    }
}

def setupProdVerticalCicdNamespacesAndJenkins(def projectInfo, def prodCicdJenkinsCredsUrl) {
    stage('Setting up prod Jenkins') {
        pushSshCredentialstToJenkins(projectInfo.prodCicdNamespace, prodCicdJenkinsCredsUrl, el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID)
        pushSshCredentialstToJenkins(projectInfo.prodCicdNamespace, prodCicdJenkinsCredsUrl, el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID)

        pushImageRepositoryTokenToJenkins(projectInfo.prodCicdNamespace, el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], prodCicdJenkinsCredsUrl)
        pushImageRepositoryTokenToJenkins(projectInfo.prodCicdNamespace, el.cicd["${projectInfo.PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], prodCicdJenkinsCredsUrl)
    }
}

def createCicdNamespaceAndJenkins(def projectInfo, def cicdJenkinsNamespace, def isNonProd) {
    def envs = isNonProd ? [projectInfo.devEnv] : [projectInfo.prodEnv]
    if (isNonProd) {
        envs.addAll(projectInfo.testEnvs)
        envs.add(projectInfo.preProdEnv)
    }

    def jenkinsImage = isNonProd ? el.cicd.JENKINS_IMAGE_STREAM : el.cicd.JENKINS_IMAGE_STREAM
    def nodeSelectors = isNonProd ? el.cicd.EL_CICD_MASTER_NODE_SELECTORS : el.cicd.EL_CICD_PROD_MASTER_NODE_SELECTORS
    def cascFile = isNonProd ? 'non-prod-jenkins-casc.yml' : 'prod-jenkins-casc.yml'

    def cicdMasterNamespace = isNonProd ? el.cicd.EL_CICD_MASTER_NAMESPACE :  el.cicd.EL_CICD_PROD_MASTER_NAMEPACE
    sh """
        ${pipelineUtils.shellEchoBanner("CREATING ${cicdJenkinsNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

        oc adm new-project ${cicdJenkinsNamespace} --node-selector='${nodeSelectors}'

        oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                      -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                                      -p DISABLE_ADMINISTRATIVE_MONITORS=${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                      -p JENKINS_IMAGE_STREAM_TAG=${jenkinsImage}:latest \
                                      -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                      -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                      -e TRY_UPGRADE_IF_NO_MARKER=true \
                                      -e CASC_JENKINS_CONFIG=${el.cicd.EL_CICD_JENKINS_CONTAINER_CONFIG_DIR}/${cascFile} \
                                      -n ${cicdJenkinsNamespace}

        oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${cicdMasterNamespace} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND}| oc create -f - -n ${cicdJenkinsNamespace}

        for ENV in ${envs.join(' ')}
        do
            oc get secrets -l \${ENV}-env -o yaml -n ${cicdMasterNamespace} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | oc apply -f - -n ${cicdJenkinsNamespace}
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

def pushSshCredentialstToJenkins(def cicdJenkinsNamespace, def cicdJenkinsUrl, def keyId) {
    def SECRET_FILE_NAME = "${el.cicd.TEMP_DIR}/elcicdReadOnlyGithubJenkinsSshCredentials.xml"
    def credsArray = [sshUserPrivateKey(credentialsId: "${keyId}", keyFileVariable: "KEY_ID_FILE")]
    def curlCommand = """curl -ksS -X POST -H "`cat ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt`" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${cicdJenkinsUrl}"""
    withCredentials(credsArray) {
        sh """
            ${pipelineUtils.shellEchoBanner("PUSH SSH GIT REPO PRIVATE KEY TO ${cicdJenkinsNamespace} JENKINS")}

            cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml | sed 's/%UNIQUE_ID%/${keyId}/g' > ${SECRET_FILE_NAME}
            cat ${KEY_ID_FILE} >> ${SECRET_FILE_NAME}
            cat ${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE_NAME}

            ${maskCommand(curlCommand)}
            rm -f ${SECRET_FILE_NAME}
        """
    }
}

def pushImageRepositoryTokenToJenkins(def cicdJenkinsNamespace, def credentialsId, def cicdJenkinsUrl) {
    withCredentials([string(credentialsId: credentialsId, variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
        def curlCommand = """curl -ksS -X POST -H "`cat ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt`" -H "content-type:application/xml" --data-binary @jenkinsTokenCredentials.xml ${cicdJenkinsUrl}"""
        sh """
            ${pipelineUtils.shellEchoBanner("PUSH IMAGE REPOSITORY TOKEN ${credentialsId} TO ${cicdJenkinsNamespace} JENKINS")}

            cat ${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${credentialsId}/g" > jenkinsTokenCredentials-named.xml
            cat jenkinsTokenCredentials-named.xml | sed "s|%TOKEN%|${IMAGE_REPO_ACCESS_TOKEN}|g" > jenkinsTokenCredentials.xml

            ${maskCommand(curlCommand)}
        """
    }
}
