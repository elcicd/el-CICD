/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def selectReleaseVersion(def projectInfo, def args) {
    dir(projectInfo.projectModule.workDir) {
        def forEachRefScript =
            "git for-each-ref --count=10 --format='%(refname:short)' --sort='-refname' 'refs/remotes/origin/*'"
        

        def releaseVersions = sh(returnStdout: true, script: "${forEachRefScript}").find {
            it ==~ projectInfo.SEMVER_REGEX
        }
        
        echo "releaseVersions: ${releaseVersions}"
        releaseVersions = (releaseVersions.size() > 5) ? releaseVersions.subList(0, 5) : releaseVersions
        echo "releaseVersions: ${releaseVersions}"
    
    
        def inputs = [choice(name: 'releaseVersion', description: "Release version of ${projectInfo.id} to deploy", choices: releaseVersions),
                      string(name: 'variant', description: 'Variant of release version [optional]', trim: true),
                      booleanParam(name: 'cleanNamespace', description: 'Uninstall the currently deployed version of the project first')]
                        
        jenkinsUtils.displayInputWithTimeout("Select release version of ${projectInfo.id} to deploy", args, inputs)
    }
}
