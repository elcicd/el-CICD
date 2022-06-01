/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the build-library pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/build-library-pipeline-template.
 *
 */

void call(Map args) {

    def projectInfo = args.projectInfo
    def library = projectInfo.libraries.find { it.name == args.libraryName }

    library.gitBranch = args.gitBranch
    library.isSnapshot = args.isSnapshot

    stage('Checkout code from repository') {
        loggingUtils.echoBanner("CLONING ${library.gitRepoName} REPO, REFERENCE: ${library.gitBranch}")

        projectUtils.cloneGitRepo(library, library.gitBranch)

        dir (library.workDir) {
            sh """
                ${shCmd.echo 'filesChanged:'}
                git diff HEAD^ HEAD --stat 2> /dev/null || :
            """
        }
    }

    def buildSteps = [el.cicd.BUILDER, el.cicd.TESTER, el.cicd.SCANNER, el.cicd.DEPLOYER]
    buildSteps.each { buildStep ->
        stage("build step: run ${buildStep} for ${library.name}") {
            loggingUtils.echoBanner("RUN ${buildStep.toUpperCase()} FOR library: ${library.name}")

            dir(library.workDir) {
                def moduleName = library[buildStep] ?: buildStep
                def builderModule = load "${el.cicd.BUILDER_STEPS_DIR}/${library.codeBase}/${moduleName}.groovy"

                switch(buildStep) {
                    case el.cicd.BUILDER:
                        builderModule.build(projectInfo, library)
                        break;
                    case el.cicd.TESTER:
                        builderModule.test(projectInfo, library)
                        break;
                    case el.cicd.SCANNER:
                        builderModule.scan(projectInfo, library)
                        break;
                    case el.cicd.DEPLOYER:
                        builderModule.deploy(projectInfo, library)
                        break;
                }
            }
        }
    }
}
