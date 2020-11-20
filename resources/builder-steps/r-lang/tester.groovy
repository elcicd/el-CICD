/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for Testing Python applications
 */

def test(def projectId, def microService) {
    writeFile file:"${el.cicd.BUILDER_STEPS_DIR}/Rtest.R",
                text: libraryResource("builder-steps/${microService.codeBase}/resources/Rtest.R")
    writeFile file:"${el.cicd.BUILDER_STEPS_DIR}/Rlint.R",
                text: libraryResource("builder-steps/${microService.codeBase}/resources/Rlint.R")

    sh """
        Rscript ${el.cicd.BUILDER_STEPS_DIR}/Rlint.R
        mkdir -p ./tests
        if [[ `ls -l ./tests/*.R | wc -l` -gt 0 ]]
        then
            echo "Found tests available to execute"
            Rscript ${el.cicd.BUILDER_STEPS_DIR}/Rtest.R
            badString='path="/'
            goodString='path="./'
            cat bad-coverage.xml | sed "s|\${badString}|\${goodString}|g" > coverage.xml
        else
            echo "No test files found in this repository"
        fi
    """
}

return this
