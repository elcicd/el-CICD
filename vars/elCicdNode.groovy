/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility class defining the Jenkins agents.
 */
 
def call(Map args, Closure body) {
    assert args.agent

    def podLabel = args.agentName ?: args.agent

    podTemplate([
        label: "${podLabel}",
        cloud: 'openshift',
        workingDir: '/tmp',
        serviceAccount: 'jenkins',
        podRetention: onFailure(),
        idleMinutes: "${el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: "${el.cicd.OCP_IMAGE_REPO}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${args.agent}:latest",
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
                resourceRequestCpu: '100m',
                resourceLimitCpu: "${el.cicd.JENKINS_AGENT_CPU_LIMIT}"
            )
        ]
    ]) {
        node(podLabel) {
            initialize()

            def preScript
            def postScript
            def onFailScript

            pipelineUtils.echoBanner("env.JOB_NAME: ${env.JOB_NAME}")

            dir(el.cicd.HOOK_SCRIPTS_DIR) {
                def preScriptFile = findFiles(glob: "**/pre-${env.JOB_BASE_NAME}.groovy")
                if (preScriptFile) {
                    preScript = load "${preScriptFile}"
                    preScript()
                }

                def postScriptFile = findFiles(glob: "**/post-${env.JOB_BASE_NAME}.groovy")
                if (preScriptFile) {
                    postScript = load "${preScriptFile}"
                }

                def onFailScriptFile = findFiles(glob: "**/onFail-${env.JOB_BASE_NAME}.groovy")
                if (onFailScriptFile) {
                    onFailScript = load "${preScriptFile}"
                }
            }

            try {
                body()
            }
            catch (Exception e) {
                if (onFailScriptFile) {
                    onFailScript()
                }

                throw e
            }

            if (postScript) {
                postScript()
            }
        }
    }
}

def initialize() {
    stage('Initializing') {
        pipelineUtils.echoBanner("INITIALIZING...")

        el.cicd.PROJECT_INFO_DIR = "${WORKSPACE}/el-CICD-project-repository"
        el.cicd.AGENTS_DIR = "${el.cicd.PROJECT_INFO_DIR}/agents"
        el.cicd.HOOK_SCRIPTS_DIR = "${el.cicd.PROJECT_INFO_DIR}/hookScripts"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.PROJECT_INFO_DIR}/builder-steps"
        el.cicd.PROJECT_DEFS_DIR = "${el.cicd.PROJECT_INFO_DIR}/project-defs"

        el.cicd.TEMP_DIR="/tmp/${BUILD_TAG}"
        sh """
            rm -rf ${WORKSPACE}/*
            mkdir -p ${el.cicd.TEMP_DIR}
            oc version
        """
        el.cicd.TEMPLATES_DIR="${el.cicd.TEMP_DIR}/templates"
        el.cicd.BUILDCONFIGS_DIR = "${el.cicd.TEMP_DIR}/buildconfigs"
        sh """
            mkdir -p ${el.cicd.BUILDCONFIGS_DIR}
            mkdir -p ${el.cicd.TEMPLATES_DIR}
        """

        el.cicd.RELEASE_VERSION_PREFIX = 'v'

        el.cicd = el.cicd.asImmutable()

        dir (el.cicd.PROJECT_INFO_DIR) {
            git url: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY,
                branch: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }
    }
}