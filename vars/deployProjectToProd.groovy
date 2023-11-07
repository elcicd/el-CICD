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

    stage('Confirm production deployment') {
        def msg = loggingUtils.createBanner(
            // "${promotionOrDeploymentMsg} TO PRODUCTION",
            // "REGION: ${projectInfo.releaseRegion ?: el.cicd.UNDEFINED}",
            // '',
            // "===========================================",
            // '',
            // '-> Microservices included in this release:',
            // projectInfo.componentsInRelease.collect { it.name }.join(', '),
            // '',
            // deployAllMsg,
            // projectInfo.componentsToDeploy.collect { it.name }.join(', '),
            // '',
            // '-> All other components and their associated resources NOT in this release WILL BE REMOVED!',
            // projectInfo.components.findAll { !it.releaseCandidateScmTag }.collect { it.name }.join(', '),
            // '',
            // loggingUtils.BANNER_SEPARATOR,
            // '',
            'PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION',
            '',
            'ARE YOU SURE YOU WISH TO PROCEED?'
        )

        input(msg)
    }

    stage('Deploy release') {
    }
}
