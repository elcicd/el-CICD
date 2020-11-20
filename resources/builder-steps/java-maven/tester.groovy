/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility method for running unit tests in Java Maven microservices
 */

def test(def projectInfo, def microService) {
    sh """
        export JAVA_TOOL_OPTIONS=
        mvn test
    """
}

return this