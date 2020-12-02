/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboarding a project of one or more microservices onto
 * el-CICD managed CICD pipeline.
 */

def initialize() {
    stage('Initializing') {
        pipelineUtils.echoBanner("INITIALIZING...")

        el.cicd.PROJECT_INFO_DIR = "${WORKSPACE}/el-CICD-project-repository"
        el.cicd.AGENTS_DIR = "${el.cicd.PROJECT_INFO_DIR}/agents"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.PROJECT_INFO_DIR}/builder-steps"
        el.cicd.PROJECT_DEFS_DIR = "${el.cicd.PROJECT_INFO_DIR}/project-defs"

        dir (el.cicd.PROJECT_INFO_DIR) {
            git url: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY,
                branch: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }

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
    }
}