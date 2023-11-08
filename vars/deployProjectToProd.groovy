/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deploymentVariant = args.deploymentVariant

    projectInfo.deployToEnv = projectInfo.prodEnv


    stage('Checkout project repo') {
        loggingUtils.echoBanner('CHECKOUT PROJECT REPO')

        projectInfoUtils.cloneGitRepo(projectInfo.projectModule)
    }

    stage('Choose release version') {
        loggingUtils.echoBanner('SELECT RELEASE VERSION TO DEPLOY')

        deployProjectToProdUtils.selectReleaseVersion(projectInfo, args)
    }

    stage('Confirm production manifest') {
        deployProjectToProdUtils.confirmProductionManifest(projectInfo, args)
    }

    def releaseVersionMsg = projectInfo.releaseVersion + (projectInfo.releaseProfile ? "(${projectInfo.releaseProfile})" : '')
    stage('Uninstall project in prod') {
        deployProjectToProdUtils.uninstallProjectInProd(projectInfo)
    }

    stage('Deploy project') {
        loggingUtils.echoBanner("DEPLOY ${projectInfo.id} ${releaseVersionMsg} TO ${projectInfo.deployToNamespace}")

        deployProjectToProdUtils.deployProjectToProduction(projectInfo)
    }

    stage('Summary') {
        loggingUtils.echoBanner("DEPLOYMENT SUMMARY FOR ${projectInfo.id} ${releaseVersionMsg} IN ${projectInfo.deployToNamespace}")
        sh "helm get manifest ${projectInfo.id} -n ${projectInfo.deployToNamespace}"
    }
}
