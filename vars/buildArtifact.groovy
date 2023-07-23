/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-artifact pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-artifact-pipeline-template.
 *
 */

void call(Map args) {

    def projectInfo = args.projectInfo
    def artifact = projectInfo.artifacts.find { it.name == args.artifactName }

    artifact.scmBranch = args.scmBranch
    artifact.isSnapshot = args.isSnapshot

    stage('Checkout code from repository') {
        loggingUtils.echoBanner("CLONING ${artifact.scmRepoName} REPO, REFERENCE: ${artifact.scmBranch}")

        projectInfoUtils.cloneGitRepo(artifact, artifact.scmBranch)

        dir (artifact.workDir) {
            sh """
                ${shCmd.echo 'filesChanged:'}
                git diff HEAD^ HEAD --stat 2> /dev/null || :
            """
        }
    }

    def buildSteps = [el.cicd.BUILDER, el.cicd.TESTER, el.cicd.SCANNER, el.cicd.DEPLOYER]
    buildSteps.each { buildStep ->
        stage("build step: run ${buildStep} for ${artifact.name}") {
            loggingUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR artifact: ${artifact.name}")

            dir(artifact.workDir) {
                def moduleName = artifact[buildStep] ?: buildStep
                def builderModule = load "${el.cicd.BUILDER_STEPS_DIR}/${artifact.codeBase}/${moduleName}.groovy"

                switch(buildStep) {
                    case el.cicd.BUILDER:
                        builderModule.build(projectInfo, artifact)
                        break;
                    case el.cicd.TESTER:
                        builderModule.test(projectInfo, artifact)
                        break;
                    case el.cicd.SCANNER:
                        builderModule.scan(projectInfo, artifact)
                        break;
                    case el.cicd.DEPLOYER:
                        builderModule.deploy(projectInfo, artifact)
                        break;
                }
            }
        }
    }
}
