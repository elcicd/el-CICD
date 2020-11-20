/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboarding a project of one or more microservices onto
 * el-CICD managed CICD pipeline.
 */

def initialize() {
    stage('Initializing') {
        pipelineUtils.echoBanner("INITIALIZING...")

        el.cicd.TEMP_DIR="/tmp/${BUILD_TAG}"
        el.cicd.TEMPLATES_DIR="${el.cicd.TEMP_DIR}/templates"
        el.cicd.BUILDCONFIGS_DIR = "${el.cicd.TEMP_DIR}/buildconfigs"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.TEMP_DIR}/builder-steps"
        sh """
            rm -rf ${WORKSPACE}/*
            mkdir -p ${el.cicd.TEMP_DIR}
            mkdir -p ${el.cicd.BUILDCONFIGS_DIR}
            mkdir -p ${el.cicd.BUILDER_STEPS_DIR}
            mkdir -p ${el.cicd.TEMPLATES_DIR}
            oc version
        """

        el.cicd.RELEASE_VERSION_PREFIX = 'v'

        el.cicd = el.cicd.asImmutable()

        writeFile file:"${el.cicd.TEMPLATES_DIR}/AuthBearerHeader-template.txt", text: libraryResource('templates/AuthBearerHeader-template.txt')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-postfix.json", text: libraryResource('templates/githubSshCredentials-postfix.json')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/githubSshCredentials-prefix.json", text: libraryResource('templates/githubSshCredentials-prefix.json')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/githubWebhook-template.json", text: libraryResource('templates/githubWebhook-template.json')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-postfix.xml", text: libraryResource('templates/jenkinsSshCredentials-postfix.xml')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsSshCredentials-prefix.xml", text: libraryResource('templates/jenkinsSshCredentials-prefix.xml')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/jenkinsTokenCredentials-template.xml", text: libraryResource('templates/jenkinsTokenCredentials-template.xml')
        writeFile file:"${el.cicd.TEMPLATES_DIR}/kustomization-template.yml", text: libraryResource('templates/kustomization-template.yml')
    }
}