/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-to-dev pipeline.  Called inline from the
 * a realized el-CICD/buildconfigs/build-and-deploy-microservices-pipeline-template.
 *
 */
agentDockerfiles = [base: 'Dockerfile.base', 
                    'java-maven': 'Dockerfile.java-maven',
                    python: 'Dockerfile.python',
                    'r-lang': 'Dockerfile.R']

def call(Map args) {
    elCicdCommons.initialize()

    stage("Write Dockerfiles to Agent") {
        agentDockerfiles.values().each { dockerFile ->
            writeFile file:"${el.cicd.AGENTS_DIR}/dockerFile", text: libraryResource("agents/${dockerFile}")
        }
    }

    stage("Create All Agents") {
        dir(el.cicd.AGENTS_DIR) {
            if (!args.ignoreBase) {
                sh "oc import-image jenkins -n openshift"
            }

            agentDockerfiles.each { agentName, dockerFile ->
                if (!args.ignoreBase || agentName != 'base') {
                    sh """
                        if [[ -z \$(oc get --ignore-not-found bc/jenkins-agent-el-cicd-${agentName}) ]]
                        then 
                            cat ./${dockerFile} | oc new-build -D - --name jenkins-agent-el-cicd-${agentName} -n openshift
                        else
                            oc start-build jenkins-agent-el-cicd-${agentName} 
                        fi
                        sleep 10
                    """
                }
            }
        }
    }
    
}
