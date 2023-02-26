/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the redeploy component pipeline methods.
 */
 
 def checkoutAllRepos(def projectInfo) {
    def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.component}{":"}{.data.deployment-branch}{" "}'
    def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.deployToNamespace}"
    def msNameDepBranch = sh(returnStdout: true, script: script).split(' ')
    def branchPrefix = "refs/remotes/**/${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.deployToEnv}-*"

    def deployedMarker = '<DEPLOYED>'
    concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.components) { component ->
        dir(component.workDir) {
            def msToBranch = msNameDepBranch.find { it.startsWith("${component.name}:${branchPrefix}") }
            component.deploymentBranch = msToBranch ? msToBranch.split(':')[1] : ''
            component.deploymentImageTag = component.deploymentBranch.replaceAll("${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')
            
            def branchesAndTimesScript =
                "git for-each-ref --count=5 --format='%(refname:short) (%(committerdate))' --sort='-committerdate' '${branchPrefix}'"
            def branchesAndTimes = sh(returnStdout: true, script: branchesAndTimesScript).trim()
            branchesAndTimes = branchesAndTimes.replaceAll("origin/${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')

            def deployLine
            branchesAndTimes.split('\n').each { line ->
                deployLine = !deployLine && line.startsWith(component.deploymentImageTag) ? line : deployLine
            }
            
            component.deployBranchesAndTimes =
                deployLine ? branchesAndTimes.replace(deployLine, "${deployLine} ${deployedMarker}") : branchesAndTimes
        }
    }
 }
 
 def selectComponentsToRedeploy(def projectInfo) {
    def inputs = []
    projectInfo.components.each { component ->
        inputs += choice(name: component.name,
                            description: "status: ${component.status}",
                            choices: "${el.cicd.IGNORE}\n${component.deployBranchesAndTimes}\n${el.cicd.REMOVE}")
    }

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select components to redeploy in ${projectInfo.deployToEnv}", inputs)

    def willRedeployOrRemove = false
    projectInfo.componentsToRedeploy = projectInfo.components.findAll { component ->
        def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
        component.remove = (answer == el.cicd.REMOVE)
        component.redeploy = (answer != el.cicd.IGNORE && answer != el.cicd.REMOVE)
        

        if (component.redeploy) {
            component.deploymentImageTag = (answer =~ "${projectInfo.deployToEnv}-[0-9a-z]{7}")[0]
            component.srcCommitHash = component.deploymentImageTag.split('-')[1]
            component.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${component.deploymentImageTag}"
        }

        willRedeployOrRemove = willRedeployOrRemove || answer
        return component.redeploy
    }

    if (!willRedeployOrRemove) {
        loggingUtils.errorBanner("NO COMPONENTS SELECTED FOR REDEPLOYMENT OR REMOVAL FOR ${projectInfo.deployToEnv}")
    }
 }