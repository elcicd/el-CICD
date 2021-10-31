/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * shCmd.echo custom Jenkins echo'ing text in in shell script
 */

def call(Object... msgs) {
    msgs = msgs ? msgs.collect { "echo \"${it.toString()}\";" }.join(' ') : 'echo;'
    return "{ ${msgs} } 2> /dev/null"
}