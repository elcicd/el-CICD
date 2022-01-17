/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility class defining the Jenkins agents' pods, executing the pipeline code on agent pods, el-CICD scripting framework around pods.
 * Also processes el-CICD metadata into global map.
 */

import groovy.transform.Field

@Field
static def cicd = [:]

def initMetaData(Map metaData) {
    cicd.putAll(metaData)

    cicd.TEST_ENVS = cicd.TEST_ENVS ? cicd.TEST_ENVS.split(':') : []

    cicd.devEnv = cicd.DEV_ENV.toLowerCase()
    cicd.testEnvs = cicd.TEST_ENVS.collect { it.toLowerCase() }
    cicd.preProdEnv = cicd.PRE_PROD_ENV.toLowerCase()

    cicd.hotfixEnv = cicd.HOTFIX_ENV.toLowerCase()

    cicd.nonProdEnvs = [cicd.devEnv]
    cicd.nonProdEnvs.addAll(cicd.testEnvs)
    cicd.nonProdEnvs.add(cicd.preProdEnv)

    cicd.prodEnv = cicd.PROD_ENV.toLowerCase()

    cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX = "${cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}|${el.cicd.CLUSTER_WILDCARD_DOMAIN}".toString()

    cicd.CLEAN_K8S_RESOURCE_COMMAND = "egrep -v -h 'namespace:|creationTimestamp:|uid:|selfLink:|resourceVersion:|generation:'"

    cicd.OKD_CLEANUP_RESOURCE_LIST='deploymentconfig,deploy,svc,hpa,configmaps,sealedsecrets,ingress,routes,cronjobs'

    cicd = cicd.asImmutable()
}

def node(Map args, Closure body) {
    assert args.agent

    def podLabel = args.agentName ?: args.agent

    def secretVolume = args.isBuild ?
        [secretVolume(secretName: "${el.cicd.EL_CICD_BUILD_SECRETS_NAME}", mountPath: "${el.cicd.BUILDER_SECRETS_DIR}/")] : []

    podTemplate([
        label: "${podLabel}",
        cloud: 'openshift',
        serviceAccount: 'jenkins',
        podRetention: onFailure(),
        idleMinutes: "${el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
        runAsUser: '1001',
        runAsGroup: '0',
        yaml:"""
            spec:
              containers:
              - name: jnlp
                image: ${el.cicd.OCP_IMAGE_REPO}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${args.agent}:latest
                args:
                - '\${computer.jnlpmac} \${computer.name}'
                imagePullPolicy: Always
                resources:
                limits:
                    cpu: "${el.cicd.JENKINS_AGENT_CPU_LIMIT}"
                    memory: ${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}
                requests:
                    cpu: ${el.cicd.JENKINS_AGENT_CPU_REQUEST}
                    memory: ${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}
                securityContext:
                  runAsUser: 1001
                  runAsGroup: 0
                  capabilities:
                    add:
                    - SET_UID
                    - SET_GID
        """
        // containers: [
        //     containerTemplate(
        //         name: 'jnlp',
        //         image: "${el.cicd.OCP_IMAGE_REPO}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${args.agent}:latest",
        //         alwaysPullImage: true,
        //         args: '${computer.jnlpmac} ${computer.name}',
        //         resourceRequestMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
        //         resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
        //         resourceRequestCpu: "${el.cicd.JENKINS_AGENT_CPU_REQUEST}",
        //         resourceLimitCpu: "${el.cicd.JENKINS_AGENT_CPU_LIMIT}"
        //     )
        // ],
        //volumes: secretVolume
    ]) {
        node(podLabel) {
            try {
                initializeStage()

                runHookScript(el.cicd.PRE, args)

                if (args.projectId) {
                    args.projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
                }

                runHookScript(el.cicd.INIT, args)

                body.call(args)

                runHookScript(el.cicd.ON_SUCCESS, args)
            }
            catch (Exception | AssertionError exception) {
                (exception instanceof Exception) ?
                    pipelineUtils.echoBanner("!!!! JOB FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}") :
                    pipelineUtils.echoBanner("!!!! JOB ASSERTION FAILED !!!!", "", "ASSERTION: ${exception}")

                runHookScript(el.cicd.ON_FAIL, args, exception)

                throw exception
            }
            finally {
                runHookScript(el.cicd.POST, args)
            }
        }
    }
}

def runHookScript(def prefix, def args) {
    runHookScript(prefix, args, null)
}

def runHookScript(def prefix, def args, def exception) {
    pipelineUtils.spacedEcho("Searching in hook-scripts directory for ${prefix}-${args.pipelineTemplateName}.groovy...")

    dir(el.cicd.HOOK_SCRIPTS_DIR) {
        def hookScriptFile = findFiles(glob: "**/${prefix}-${args.pipelineTemplateName}.groovy")
        if (hookScriptFile) {
            def hookScript = load hookScriptFile[0].path

            pipelineUtils.spacedEcho("hook-script ${prefix}-${args.pipelineTemplateName}.groovy found: RUNNING...")

            exception ?  hookScript(exception, args) : hookScript(args)

            pipelineUtils.spacedEcho("hook-script ${prefix}-${args.pipelineTemplateName}.groovy COMPLETE")
        }
        else {
            pipelineUtils.spacedEcho("hook-script ${prefix}-${args.pipelineTemplateName}.groovy NOT found...")
        }
    }
}

def initializeStage() {
    stage('Initializing') {
        pipelineUtils.echoBanner("INITIALIZING...")

        el.cicd.CONFIG_DIR = "${WORKSPACE}/el-CICD-config"
        el.cicd.JENKINS_CONFIG_DIR = "${el.cicd.CONFIG_DIR}/jenkins"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.CONFIG_DIR}/builder-steps"
        el.cicd.OKD_TEMPLATES_DIR = "${el.cicd.CONFIG_DIR}/managed-okd-templates"
        el.cicd.RESOURCE_QUOTA_DIR = "${el.cicd.CONFIG_DIR}/resource-quotas"
        el.cicd.HOOK_SCRIPTS_DIR = "${el.cicd.CONFIG_DIR}/hook-scripts"
        el.cicd.PROJECT_DEFS_DIR = "${el.cicd.CONFIG_DIR}/project-defs"

        el.cicd.TEMP_DIR="/tmp/${BUILD_TAG}"
        sh """
            rm -rf ${WORKSPACE}
            mkdir -p ${WORKSPACE}
            mkdir -p ${el.cicd.TEMP_DIR}
            oc version
        """

        el.cicd.TEMPLATES_DIR="${el.cicd.TEMP_DIR}/templates"
        el.cicd.BUILDCONFIGS_DIR = "${el.cicd.TEMP_DIR}/buildconfigs"
        sh """
            mkdir -p ${el.cicd.BUILDCONFIGS_DIR}
            mkdir -p ${el.cicd.TEMPLATES_DIR}
        """

        el.cicd.CM_META_INFO_POSTFIX = 'meta-info'

        el.cicd.RELEASE_VERSION_PREFIX = 'v'

        el.cicd = el.cicd.asImmutable()

        dir (el.cicd.CONFIG_DIR) {
            git url: el.cicd.EL_CICD_CONFIG_REPOSITORY,
                branch: el.cicd.EL_CICD_CONFIG_REPOSITORY_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }
    }
}

def getDevPipelines() {
    return ['build-and-deploy-microservices-pipeline-template.yml']
}

def getTestPipelines() {
    return ['create-release-candidate-pipeline-template.yml',
            'microservice-promotion-removal-pipeline-template.yml',
            'microservice-redeploy-removal-pipeline-template.yml',
            'redeploy-release-candidate-pipeline-template.yml']
}

def getNonProdPipelines() {
    def pipelines = getDevPipelines()
    pipelines.addAll(getTestPipelines())
    return pipelines
}

def getProdPipelines() {
    return ['deploy-to-production-pipeline-template.yml']
}