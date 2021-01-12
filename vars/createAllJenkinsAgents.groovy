/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the create-all-jenkins-agents pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-all-jenkins-agents-pipeline-template.
 *
 */

def call(Map args) {
    el.initializeStage()

    def agentDockerfiles

    stage('Create All Agents') {
        def agentNames = [el.cicd.JENKINS_AGENT_DEFAULT]
        if (!args.buildBaseOnly) {
            agentNames.addAll(el.cicd.JENKINS_AGENT_NAMES.tokenize(':'))
        }

        pipelineUtils.echoBanner('CREATE JENKINS AGENTS (in the following order):', agentNames.join(', '))

        dir(el.cicd.JENKINS_CONFIG_DIR) {
            agentNames.each { agentName ->
                sh """
                    if [[ ! -n \$(oc get bc ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} --ignore-not-found -n openshift) ]]
                    then
                        oc new-build --name ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} --binary=true --strategy=docker -n openshift
                    fi
                    ${pipelineUtils.shellEchoBanner("Starting Agent Build: ${agentName}")}

                    cat Dockerfile.${agentName} > Dockerfile
                    oc start-build ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} --from-dir=./ --wait --follow -n openshift
                    rm Dockerfile
                """
            }
        }
    }
}
