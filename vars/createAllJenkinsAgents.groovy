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
        def agentNames = el.cicd.JENKINS_AGENT_NAMES.tokenize(':')
        if (!args.ignoreDefaultAgent) {
            agentNames.add(0, el.cicd.JENKINS_AGENT_DEFAULT)
        }

        pipelineUtils.echoBanner('CREATE JENKINS AGENTS:', agentNames.join(', '))

        dir(el.cicd.AGENTS_DIR) {
            agentNames.each { agentName ->
                sh """
                    if [[ -z \$(oc get --ignore-not-found bc/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift) ]]
                    then 
                        oc new-build --name ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} \
                                     --binary=true \
                                     --strategy=docker \
                                     --to-docker \
                                     --to=${el.cicd.OCP_IMAGE_REPO}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName}:latest \
                                     -n openshift
                    fi

                    cp ./Dockerfile.${agentName} ${el.cicd.TEMP_DIR}/Dockerfile
                    oc start-build ${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${agentName} --from-file=${el.cicd.TEMP_DIR}/Dockerfile --wait -n openshift
                """
            }
        }
    }
}
