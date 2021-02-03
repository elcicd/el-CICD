/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * maskCommand custom Jenkins step for masking secrets in a bash command
 */

def call(String command) {
    return """
        { set +x; }
        echo '${command}' 1>&2
        2> /dev/null
        ${command}
        set -x
    """
}