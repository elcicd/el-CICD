/*
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

        if (!cicdProjectsExist.contains(cicdNamespace)) {
            stage('Creating CICD namespaces and rbacGroup Jenkins') {
                createCicdNamespaceAndJenkins(cicdNamespace, projectInfo.rbacGroup, isNonProd)
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
        def templates = isNonProd ? 'promotion-removal-pipeline-template.yml redeploy-removal-pipeline-template.yml ' +
                'production-manifest-pipeline-template.yml redeploy-release-candidate-pipeline-template.yml ' +
                'build-and-deploy-microservices-pipeline-template.yml' : 
                'deploy-to-production-pipeline-template.yml'
        pipelineUtils.echoBanner('CREATING SHARED PIPELINES:', templates)
        
        def namespace = isNonProd ? projectInfo.nonProdCicdNamespace : projectInfo.prodCicdNamespace
        
        createSharedPipelines(templates, namespace)
    }
}

def setupNonProdVerticalCicdNamespacesAndJenkins(def projectInfo, def nonProdCicdJenkinsCredsUrl) {
    stage('Setting up non-prod Jenkins') {
        pushSshCredentialstToJenkins(projectInfo.nonProdCicdNamespace, nonProdCicdJenkinsCredsUrl, el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID)
        pushSshCredentialstToJenkins(projectInfo.nonProdCicdNamespace, nonProdCicdJenkinsCredsUrl, el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID)

        def ids = []
        projectInfo.NON_PROD_ENVS.each { ENV ->
            if (!ids.contains(ENV)) {
                pushImageRepositoryTokenToJenkins(projectInfo.nonProdCicdNamespace, el.cicd["${ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], nonProdCicdJenkinsCredsUrl)
                ids += ENV
            }
        }
        // verticalBootstrap.pushSonarQubeTokenToNonProdJenkins(projectInfo.nonProdCicdNamespace, nonProdCicdJenkinsCredsUrl)
    }
}

def setupProdVerticalCicdNamespacesAndJenkins(def projectInfo, def prodCicdJenkinsCredsUrl) {
    stage('Setting up prod Jenkins') {
        pushSshCredentialstToJenkins(projectInfo.prodCicdNamespace, prodCicdJenkinsCredsUrl, el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID)
        pushSshCredentialstToJenkins(projectInfo.prodCicdNamespace, prodCicdJenkinsCredsUrl, el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID)

        pushImageRepositoryTokenToJenkins(projectInfo.prodCicdNamespace, el.cicd["${projectInfo.PRE_PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], prodCicdJenkinsCredsUrl)
        pushImageRepositoryTokenToJenkins(projectInfo.prodCicdNamespace, el.cicd["${projectInfo.PROD_ENV}_IMAGE_REPO_ACCESS_TOKEN_ID"], prodCicdJenkinsCredsUrl)
    }
}

def createCicdNamespaceAndJenkins(def cicdJenkinsNamespace, def rbacGroup, def isNonProd) {
    def envs = isNonProd ? [el.cicd.devEnv] + el.cicd.testEnvs : [el.cicd.prodEnv]
    def nodeSelectors = isNonProd ? el.cicd.EL_CICD_NON_PROD_MASTER_NODE_SELECTORS : el.cicd.EL_CICD_PROD_MASTER_NODE_SELECTORS

    def cicdMasterNamespace = isNonProd ? el.cicd.EL_CICD_NON_PROD_MASTER_NAMEPACE :  el.cicd.EL_CICD_PROD_MASTER_NAMEPACE
    sh """
        ${pipelineUtils.shellEchoBanner("CREATING ${cicdJenkinsNamespace} PROJECT AND JENKINS FOR THE ${rbacGroup} GROUP")}

        oc adm new-project ${cicdJenkinsNamespace} --node-selector='${nodeSelectors}'

        oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                      -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY}  \
                                      -p DISABLE_ADMINISTRATIVE_MONITORS==${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS}  \
                                      -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops -n ${cicdJenkinsNamespace}

        oc get cm ${el.cicd.EL_CICD_META_INFO_NAME} -o yaml -n ${cicdMasterNamespace} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND}| oc create -f - -n ${cicdJenkinsNamespace}

        for ENV in ${envs.join(' ')}
        do
            oc get sealedsecrets -l \${ENV}-env -o yaml -n ${cicdMasterNamespace} | ${el.cicd.CLEAN_K8S_RESOURCE_COMMAND} | oc apply -f - -n ${cicdJenkinsNamespace}
        done

        oc policy add-role-to-group admin ${rbacGroup} -n ${cicdJenkinsNamespace}
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

            cat ${el.cicd.EL_CICD_DIR}/resources/jenkinsSshCredentials-prefix.xml | sed 's/%UNIQUE_ID%/${keyId}/g' > ${SECRET_FILE_NAME}
            cat ${KEY_ID_FILE} >> ${SECRET_FILE_NAME}
            cat ${el.cicd.EL_CICD_DIR}/resources/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE_NAME}

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

            cat ${el.cicd.EL_CICD_DIR}/resources/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${credentialsId}/g" > jenkinsTokenCredentials-named.xml
            cat jenkinsTokenCredentials-named.xml | sed "s|%TOKEN%|${IMAGE_REPO_ACCESS_TOKEN}|g" > jenkinsTokenCredentials.xml

            ${maskCommand(curlCommand)}
        """
    }
}

def pushSonarQubeTokenToNonProdJenkins(def nonProdCicdNamespace, def cicdJenkinsUrl) {
    withCredentials([string(credentialsId: 'sonarqube-access-token', variable: 'SONARQUBE_ACCESS_TOKEN')]) {
        def curlCommand = """curl -ksS -X POST -H "`cat ${el.cicd.TEMP_DIR}/AuthBearerHeader.txt`" -H "content-type:application/xml" --data-binary @jenkinsTokenCredentials.xml ${nonProdCicdJenkinsUrl}"""
        sh """
            ${pipelineUtils.shellEchoBanner("PUSH SONARQUBE TOKEN TO ${nonProdCicdNamespace} JENKINS")}

            cat ${el.cicd.EL_CICD_DIR}/resources/jenkinsTokenCredentials-template.xml | sed "s/%ID%/sonarqube-access-token/g" > jenkinsTokenCredentials-named.xml
            cat jenkinsTokenCredentials-named.xml | sed "s/%TOKEN%/${SONARQUBE_ACCESS_TOKEN}/g" > jenkinsTokenCredentials.xml

            ${maskCommand(curlCommand)}
        """
    }
}

def createSharedPipelines(def templates, def cicdJenkinsNamespace) {
    dir ("${el.cicd.EL_CICD_DIR}/buildconfigs") {
        sh """
            for FILE in ${templates}
            do
                oc process -f \${FILE} -p EL_CICD_META_INFO_NAME=${el.cicd.EL_CICD_META_INFO_NAME} \
                                       -p EL_CICD_GIT_REPO=${el.cicd.EL_CICD_GIT_REPO} \
                                       -p EL_CICD_BRANCH_NAME=${el.cicd.EL_CICD_BRANCH_NAME} | \
                    oc apply -f - -n ${cicdJenkinsNamespace}
            done
        """
    }
}
