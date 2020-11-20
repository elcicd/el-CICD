/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility method for building Java Maven microservices
 *
 */

def build(def projectInfo, def microService) {
    sh """
        export JAVA_TOOL_OPTIONS=
        mvn -DskipTests --batch-mode clean package
    """
}

return this