/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the create-all-jenkins-agents pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-all-jenkins-agents-pipeline-template.
 *
 */

def call(Map args) {
    elCicdCommons.initialize()

    def agentDockerfiles

    stage('Update Jenkins') {
        if (args.updateJenkinsImage) {
            pipelineUtils.echoBanner('UPDATE JENKINS IMAGE')

            sh "oc import-image jenkins -n openshift"
        }
        else {
            pipelineUtils.echoBanner('SKIPPING UPDATE JENKINS IMAGE')
        }
    }

    stage('Create All Agents') {
        echo "args: ${args}"
        echo "args.ignoreDefaultAgent: ${args.ignoreDefaultAgent}"

        def agentNames = el.cicd.JENKINS_AGENT_NAMES.tokenize(':')
        if (!args.ignoreDefaultAgent) {
            agentNames.push(el.cicd.JENKINS_AGENT_DEFAULT)
        }

        pipelineUtils.echoBanner('CREATE JENKINS AGENTS:', agentNames.join(', '))

        return

        dir(el.cicd.AGENTS_DIR) {
            agentNames.each { agentName ->
                sh """
                    if [[ -z \$(oc get --ignore-not-found bc/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift) ]]
                    then 
                        cat ./Dockerfile.${agentName} | oc new-build -D - --name ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift
                    else
                        oc start-build ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift
                    fi
                    sleep 10

                    oc logs -f bc/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift --pod-running-timeout=1m
                """
            }
        }
    }
}
