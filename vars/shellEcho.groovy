/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * shellEcho custom Jenkins echo'ing text in in shell script
 */

def call(String ... msgs) {
    msgs = msgs ? msgs.collect { "echo '${it}';" }.join(' ') : 'echo;'
    return "{ ${msgs} } 2> /dev/null"
}