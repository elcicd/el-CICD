/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
 
 def verifyVersionTagDoesNotExistInScm(def projectInfo) {
    projectInfo.components.each { component ->
        dir(component.workDir) {
            withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                def tagExists = sh(returnStdout: true,
                                   script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git ls-remote ${component.scmRepoUrl} --tags ${projectInfo.versionTag}"))
                if (tagExists) {
                    loggingUtils.errorBanner("TAGGING FAILED: Version tag ${projectInfo.versionTag} exists in SCM, and CANNOT be reused")
                }
            }
        }
    }
 }
 
 def verifyReleaseCandidateImagesDoNotExistInImageRegistry(def projectInfo) {
    def imageExists = true
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                     usernameVariable: 'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                     passwordVariable: 'PRE_PROD_IMAGE_REGISTRY_PWD')]) {
        imageExists = projectInfo.components.find { component ->
            def verifyImageCmd = shCmd.verifyImage(projectInfo.PRE_PROD_ENV,
                                                   'PRE_PROD_IMAGE_REGISTRY_USERNAME',
                                                   'PRE_PROD_IMAGE_REGISTRY_PWD',
                                                    component.id,
                                                    projectInfo.versionTag)

            return sh(returnStdout: true, script: verifyImageCmd)
        }
    }

    if (imageExists) {
        def msg = "Version tag exists in pre-prod image registry for ${projectInfo.id} in ${projectInfo.PRE_PROD_ENV}, and cannot be reused"
        loggingUtils.errorBanner("CREATING RELEASE CANDIDATE VERSION ${projectInfo.versionTag} FAILED: ", msg)
    }
 }
 
 def checkoutAllPreProdDeployedComponents(def projectInfo) {
    def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.component}{":"}{.data.src-commit-hash}{" "}'
    def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.preProdNamespace}"
    def msNameHashData = sh(returnStdout: true, script: script)
    
    def components = projectInfo.components.findAll { component ->
        return msNameHashData.find("${component.name}:[0-9a-z]{7}")        
    }
    
    concurrentUtils.runCloneGitReposStages(projectInfo, components) { component ->
        component.releaseCandidateAvailable = true

        component.deploymentBranch = projectInfoUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.preProdEnv)
        dir(component.workDir) {
            sh "git checkout ${component.deploymentBranch}"
        }
    }
 }