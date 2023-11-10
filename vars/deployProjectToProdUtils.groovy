/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def selectReleaseVersion(def projectInfo, def args) {
    dir(projectInfo.projectModule.workDir) {
        def forEachRefScript =
            "git for-each-ref --format='%(refname:lstrip=3)' --sort='-refname' 'refs/remotes/origin/*'"


        def releaseVersions = sh(returnStdout: true, script: "${forEachRefScript}").split(/\s+/)

        releaseVersions = releaseVersions.findAll { it ==~ projectInfoUtils.SEMVER_REGEX }.take(5)

        def deploymentStrategy = [el.cicd.ROLLING, el.cicd.RECREATE]
        def inputs = [choice(name: 'releaseVersion', description: "Release version of ${projectInfo.id} to deploy", choices: releaseVersions),
                      string(name: 'releaseProfile', description: 'Profile of release version; e.g. "east", "west", "fr", "en" [optional]', trim: true),
                      choice(name: 'deploymentStrategy', description: "Deployment Strategy", choices: deploymentStrategy)]

        def deploymentInfo = jenkinsUtils.displayInputWithTimeout("Select release version of ${projectInfo.id} to deploy to ${projectInfo.PROD_ENV}", args, inputs)

        projectInfo.releaseVersion = deploymentInfo.releaseVersion
        projectInfo.releaseProfile = deploymentInfo.releaseProfile
        projectInfo.deploymentStrategy = deploymentInfo.deploymentStrategy
    }
}

 def confirmProductionManifest(def projectInfo, def args) {
    dir(projectInfo.projectModule.workDir) {
        def namespaceKey = projectInfo.releaseProfile ?: projectInfo.prodEnv
        projectInfo.deployToNamespace = projectInfo.prodNamespace[namespaceKey]

        def compNamesToDeploy = sh(returnStdout: true, script: "ls -d charts/* | xargs -n 1 basename | tr '\n' ' '")
        def compNamesToRemove = projectInfo.components.findAll { !compNamesToDeploy.contains(" ${it.name} ") }.collect{ it.name }.join(' ')

        def profileMsg = projectInfo.releaseProfile ? "(${projectInfo.releaseProfile})" : ''
        def msg = loggingUtils.createBanner(
            "CONFIRM DEPLOYMENT OF ${projectInfo.id} ${projectInfo.releaseVersion}${profileMsg} TO ${projectInfo.deployToNamespace}",
            '',
            loggingUtils.BANNER_SEPARATOR,
            '',
            '-> SELECTED COMPONENTS IN THIS VERSION WILL BE DEPLOYED:',
            compNamesToDeploy,
            '',
            '---',
            '',
            '-> COMPONENTS TO BE REMOVED AND/OR IGNORED IN THIS VERSION:',
            compNamesToRemove,
            '',
            loggingUtils.BANNER_SEPARATOR,
            '',
            'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
            '',
            "Should ${projectInfo.id} ${projectInfo.releaseVersion}${profileMsg} be deployed to production?",
        )
    
        jenkinsUtils.displayInputWithTimeout(msg, args)
    }
 }

 def uninstallProjectInProd(def projectInfo) {
    if (projectInfo.deploymentStrategy == el.cicd.RECREATE) {
        def chartInstalledScript = "helm list -q -n ${projectInfo.deployToNamespace} | grep -E '^${projectInfo.id}\$' || :"
        def chartInstalled = sh(returnStdout: true, script: chartInstalledScript)
        if (chartInstalled) {
            loggingUtils.echoBanner("RECREATE ${projectInfo.id} in ${projectInfo.deployToNamespace}")
            sh "helm uninstall ${projectInfo.id} -n ${projectInfo.deployToNamespace}"
        }
        else {
            echo "--> Nothing to recreate in ${projectInfo.deployToNamespace}; Skipping..."
        }
    }
    else {
            echo "--> Rolling eployment strategy selected; Skipping..."
    }
 }

 def deployProjectToProduction(def projectInfo) {
    def postRendererArgs = projectInfo.releaseProfile ? "${projectInfo.prodEnv},${projectInfo.releaseProfile}" : projectInfo.prodEnv
    dir(projectInfo.projectModule.workDir) {
        sh """
            helm upgrade --install --atomic --cleanup-on-fail --history-max=2 --create-namespace \
                    --post-renderer ./kustomize-project.sh \
                    --post-renderer-args ${postRendererArgs} \
                    -n ${projectInfo.deployToNamespace} \
                    ${projectInfo.id} .
        """
    }
 }
