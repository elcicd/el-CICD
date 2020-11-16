/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboarding a project of one or more microservices onto
 * el-CICD managed CICD pipeline.
 */

def initialize() {
    stage('Initializing') {
        pipelineUtils.echoBanner("INITIALIZING...")

        el.cicd.EL_CICD_DIR = "${WORKSPACE}/el-CICD"
        el.cicd.EL_CICD_BUILDER_STEPS_DIR = "${el.cicd.EL_CICD_DIR}/builder-steps"
        el.cicd.TEMP_DIR="/tmp/${BUILD_TAG}"
        sh """
            rm -rf ${WORKSPACE}/*
            mkdir -p ${el.cicd.TEMP_DIR}
            oc version
        """

        el.cicd.RELEASE_VERSION_PREFIX = 'v'

        el.cicd = el.cicd.asImmutable()
    }
}

def cloneElCicdRepo() {
    stage('Clone elcicd repo') {
        pipelineUtils.echoBanner("CLONING el-CICD REPO, REFERENCE: ${el.cicd.EL_CICD_BRANCH_NAME}")

        dir (el.cicd.EL_CICD_DIR) {
            git url: el.cicd.EL_CICD_GIT_REPO,
                branch: el.cicd.EL_CICD_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }
    }
}