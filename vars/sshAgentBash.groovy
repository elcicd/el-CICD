/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * sshAgentBash custom Jenkins step for generating sshkey's
 */


def call(def sshKeyId, def ... commands) {
    return "ssh-agent bash -c 'ssh-add \${${sshKeyId}} ; ${commands.join('; ')}'"
}