/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the create-all-jenkins-agents pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-all-jenkins-agents-pipeline-template.
 *
 */

def call(Map args) {
    elCicdCommons.initialize()

    def agentDockerfiles = findFiles(glob: "${el.cicd.AGENTS_DIR}/Dockerfile.*")
    agentDockerfiles = agentDockerfiles.collectEntries { file ->
        [file.name.substring(file.name.lastIndexOf('.')): file.name]
    }

    stage('Update Jenkins') {
        if (!args.ignoreJenkinsImage) {
            pipelineUtils.echoBanner('UPDATE JENKINS IMAGE')

            sh "oc import-image jenkins -n openshift"
        }
    }

    stage('Create All Agents') {
        pipelineUtils.echoBanner('CREATE JENKINS AGENTS:', agentDockerfiles)

        if (args.ignoreBase) {
            agentDockerfiles.remove('base')
        }

        dir(el.cicd.AGENTS_DIR) {
            agentDockerfiles.each { agentName, dockerFile ->
                sh """
                    if [[ -z \$(oc get --ignore-not-found bc/jenkins-agent-el-cicd-${agentName} -n openshift) ]]
                    then 
                        cat ./${dockerFile} | oc new-build -D - --name jenkins-agent-el-cicd-${agentName} -n openshift
                    else
                        oc start-build jenkins-agent-el-cicd-${agentName} 
                    fi
                    sleep 10

                    oc logs -f bc/jenkins-agent-el-cicd-${agentName} -n openshift
                """
            }
        }
    }
}
