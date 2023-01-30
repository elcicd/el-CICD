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
    stage('Init Metadata') {
        loggingUtils.echoBanner("INITIALIZING METADATA...")
        
        el.cicd.putAll(metaData)

        el.cicd.EL_CICD_DIR = "${WORKSPACE}/${el.cicd.EL_CICD_REPO}"
        el.cicd.EL_CICD_HELM_DIR = "${el.cicd.EL_CICD_DIR}/${el.cicd.DEFAULT_HELM_DIR}"
        
        el.cicd.CONFIG_DIR = "${WORKSPACE}/${el.cicd.EL_CICD_CONFIG_REPO}"
        el.cicd.CONFIG_HELM_DIR = "${el.cicd.CONFIG_DIR}/${el.cicd.DEFAULT_HELM_DIR}"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.CONFIG_DIR}/builder-steps"
        el.cicd.SYSTEM_TEST_RUNNERS_DIR = "${el.cicd.CONFIG_DIR}/system-test-runners"
        el.cicd.HOOK_SCRIPTS_DIR = "${el.cicd.CONFIG_DIR}/hook-scripts"
        el.cicd.PROJECT_DEFS_DIR = "${el.cicd.CONFIG_DIR}/project-defs"

        el.cicd.TEMP_DIR = "/tmp/${BUILD_TAG}"
        el.cicd.TEMPLATES_DIR = "${el.cicd.TEMP_DIR}/templates"

        el.cicd.TEST_ENVS = el.cicd.TEST_ENVS ? el.cicd.TEST_ENVS.split(':') : []

        el.cicd.devEnv = el.cicd.DEV_ENV.toLowerCase()
        el.cicd.testEnvs = el.cicd.TEST_ENVS.collect { it.toLowerCase() }
        el.cicd.preProdEnv = el.cicd.PRE_PROD_ENV.toLowerCase()

        el.cicd.hotfixEnv = el.cicd.HOTFIX_ENV.toLowerCase()

        el.cicd.nonProdEnvs = [el.cicd.devEnv]
        el.cicd.nonProdEnvs.addAll(el.cicd.testEnvs)
        el.cicd.nonProdEnvs.add(el.cicd.preProdEnv)

        el.cicd.prodEnv = el.cicd.PROD_ENV.toLowerCase()

        el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX = "${el.cicd.EL_CICD_DEPLOY_KEY_TITLE_PREFIX}|${el.cicd.CLUSTER_WILDCARD_DOMAIN}".toString()

        el.cicd.CLEAN_K8S_RESOURCE_COMMAND = "egrep -v -h 'namespace:|creationTimestamp:|uid:|selfLink:|resourceVersion:|generation:'"

        el.cicd.OKD_CLEANUP_RESOURCE_LIST='deploymentconfig,deploy,svc,hpa,configmaps,sealedsecrets,ingress,routes,cronjobs'
    }
}

def node(Map args, Closure body) {
    assert args.agent
    
    def volumeDefs = [
        persistentVolumeClaim(claimName: 'jenkins-agent-home', mountPath: '/home/jenkins', readOnly: false),
        emptyDirVolume(mountPath: '/home/jenkins/agent', memory: true)
    ]

    if (args.isBuild) {
        volumeDefs += secretVolume(secretName: "${el.cicd.EL_CICD_BUILD_SECRETS_NAME}", mountPath: "${el.cicd.BUILDER_SECRETS_DIR}/")
    }
    
    podTemplate([
        label: "${args.agent}",
        cloud: 'openshift',
        serviceAccount: "${el.cicd.JENKINS_SERVICE_ACCOUNT}",
        podRetention: onFailure(),
        idleMinutes: "${el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
        imagePullSecrets: ["el-cicd-jenkins-pull-secret"],
        yaml: '''
          spec:
            securityContext:
              fsGroup: 1001
        ''',
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: "${el.cicd.JENKINS_IMAGE_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${args.agent}:latest",
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestMemory: "${el.cicd.JENKINS_AGENT_MEMORY_REQUEST}",
                resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}"
            )
        ],
        volumes: volumeDefs
    ]) {
        node(args.agent) {
            try {                
                initializePipeline()

                runHookScript(el.cicd.PRE, args)

                if (args.projectId) {
                    args.projectInfo = projectUtils.gatherProjectInfoStage(args.projectId)
                }

                runHookScript(el.cicd.INIT, args)

                body.call(args)

                runHookScript(el.cicd.ON_SUCCESS, args)
            }
            catch (Exception | AssertionError exception) {
                (exception instanceof Exception) ?
                    loggingUtils.echoBanner("!!!! JOB FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}") :
                    loggingUtils.echoBanner("!!!! JOB ASSERTION FAILED !!!!", "", "ASSERTION: ${exception}")

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
    loggingUtils.spacedEcho("Searching in hook-scripts directory for ${prefix}-${args.pipelineName}.groovy...")

    dir(el.cicd.HOOK_SCRIPTS_DIR) {
        def hookScriptFile = findFiles(glob: "**/${prefix}-${args.pipelineName}.groovy")
        if (hookScriptFile) {
            def hookScript = load hookScriptFile[0].path

            loggingUtils.spacedEcho("hook-script ${prefix}-${args.pipelineName}.groovy found: RUNNING...")

            exception ?  hookScript(exception, args) : hookScript(args)

            loggingUtils.spacedEcho("hook-script ${prefix}-${args.pipelineName}.groovy COMPLETE")
        }
        else {
            loggingUtils.spacedEcho("hook-script ${prefix}-${args.pipelineName}.groovy NOT found...")
        }
    }
}

def initializePipeline() {
    stage('Initializing pipeline') {
        loggingUtils.echoBanner("INITIALIZING PIPELINE...")
        
        sh """
            mkdir -p '${el.cicd.TEMP_DIR}'
            mkdir -p '${el.cicd.TEMPLATES_DIR}'

            ${shCmd.echo "\n=======================\n"}
            ${shCmd.echo 'OCP Runtime'}
            oc version
            ${shCmd.echo "\n=======================\n"}
            ${shCmd.echo 'Helm Version'}
            helm version
            ${shCmd.echo "\n=======================\n"}
            ${shCmd.echo 'Jenkins Service Account'}
            oc whoami
            ${shCmd.echo "\n======================="}
        """

        dir (el.cicd.EL_CICD_DIR) {
            git url: el.cicd.EL_CICD_GIT_REPO,
                branch: el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }

        dir (el.cicd.CONFIG_DIR) {
            git url: el.cicd.EL_CICD_CONFIG_GIT_REPO,
                branch: el.cicd.EL_CICD_CONFIG_GIT_REPO_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }
    }
}