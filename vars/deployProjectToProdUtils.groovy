/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def selectReleaseVersion(def projectInfo, def args) {
    dir(projectInfo.projectModule.workDir) {
        def releaseVersions = sh(returnStdout: true, script: "git branch --list").split(/\s+/).collect {
            it ==~ projectInfo.SEMVER_REGEX
        }.sort().takeRight(5).reverse()
    
    
        def inputs = [choice(name: 'releaseVersion', description: "Release version of ${projectInfo.id} to deploy", choices: releaseVersions),
                      string(name: 'variant', description: 'Variant of release version [optional]', trim: true),
                      booleanParam(name: 'cleanNamespace', description: 'Uninstall the currently deployed version of the project first')]
                        
        jenkinsUtils.displayInputWithTimeout("Select release version of ${projectInfo.id} to deploy", args, inputs)
    }
}

def cleanupPreviousRelease(def projectInfo) {
    sh """
        ${loggingUtils.shellEchoBanner("REMOVING ALL RESOURCES FOR ${projectInfo.id} THAT ARE NOT PART OF ${projectInfo.releaseVersion}")}

        oc delete ${el.cicd.OKD_CLEANUP_RESOURCE_LIST} -l el-cicd.io/projectid=${projectInfo.id},release-version!=${projectInfo.releaseVersion} -n ${projectInfo.prodNamespace}
    """

    deploymentUtils.waitingForPodsToTerminate(projectInfo.prodNamespace)
}
