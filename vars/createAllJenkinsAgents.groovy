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
    stage('gather dockerfiles') {
        dir (el.cicd.PROJECT_INFO_DIR) {
            git url: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY,
                branch: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }

        dir(el.cicd.AGENTS_DIR) {
            agentDockerfiles = findFiles(glob: "Dockerfile.*")
        
            agentDockerfiles = agentDockerfiles.collectEntries { file ->
                [(file.name.substring(file.name.lastIndexOf('.') + 1)): file.name]
            }
        }
    }


    stage('Update Jenkins') {
        if (!args.ignoreJenkinsImage) {
            pipelineUtils.echoBanner('UPDATE JENKINS IMAGE')

            sh "oc import-image jenkins -n openshift"
        }
        else {
            pipelineUtils.echoBanner('SKIPPING UPDATE JENKINS IMAGE')
        }
    }

    stage('Create All Agents') {
        if (args.ignoreBase) {
            agentDockerfiles.remove('base')
        }

        pipelineUtils.echoBanner('CREATE JENKINS AGENTS:', agentDockerfiles)

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
