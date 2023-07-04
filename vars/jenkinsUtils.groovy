/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for pushing credentials to servers and external tools.
 */

import groovy.transform.Field

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def configureCicdJenkinsUrls(def projectInfo) {
    projectInfo.jenkinsUrls = [:]
    projectInfo.jenkinsUrls.HOST = "https://${projectInfo.teamInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
}

def getImageRegistryCredentialsId(def env) {
    return "${el.cicd.IMAGE_REGISTRY_PULL_SECRET_PREFIX}${env.toLowerCase()}${el.cicd.IMAGE_REGISTRY_PULL_SECRET_POSTFIX}"
}

def displayInputWithTimeout(def inputMsg, def args, def inputs = null) {
    def cicdInfo
    def startTime = System.currentTimeMillis()
    try {
        el.runHookScript(el.cicd.PRE_USER_INPUT, args)
        timeout(time: el.cicd.JENKINS_INPUT_MINS_TIMEOUT) {
            if (inputs) {
                cicdInfo = input(message: inputMsg, parameters: inputs)
            }
            else {
                input(inputMsg)
            }
        }
        el.runHookScript(el.cicd.POST_USER_INPUT, args)
    }
    catch(FlowInterruptedException err) {
        def inputDuration = (System.currentTimeMillis() - startTime) / 1000
        def timeoutSeconds = (el.cicd.JENKINS_INPUT_MINS_TIMEOUT as Long) * 60

        if (inputDuration > timeoutSeconds) {
            def abortMsg = "${el.cicd.JENKINS_INPUT_MINS_TIMEOUT} MINUTE TIMEOUT EXCEEDED WAITING FOR USER INPUT"
            loggingUtils.errorBanner(abortMsg, '', 'EXITING PIPELINE...')
        }
        else {
            loggingUtils.errorBanner('USER ABORTED PIPELINE.  EXITING PIPELINE...')
        }
    }

    return cicdInfo
}