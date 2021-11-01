/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Image utilities
 */

/* When executed, command will echo either the image name and sha, or null if image doesn't exist. */
def verifyImage(String env, String tokenVar, String image, String tag) {
    def user = el.cicd["${env}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
    def creds = "--creds ${user}:\${${tokenVar}}"

    def tlsVerify = el.cicd["${env}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    tlsVerify = tlsVerify? "--tls-verify=${tlsVerify}" : ''

    def imageRepo = el.cicd["${env}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def imageUrl = "docker://${imageRepo}/${image}:${tag}"

    return "skopeo inspect --format '{{.Name}}({{.Digest}})' ${tlsVerify} ${creds} ${imageUrl} 2> /dev/null || :"
}

def copyImage(String fromEnv, String fromTokenVar, String fromImage, String fromTag,
               String toEnv, String toTokenVar, String toImage, String toTag)
{
    def user = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
    def srcCreds = "--src-creds ${user}:\${${fromTokenVar}}"

    user = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
    def destCreds = "--dest-creds ${user}:\${${toTokenVar}}"

    def tlsVerify = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    def srcTlsVerify = tlsVerify? "--src-tls-verify=${tlsVerify}" : ''

    tlsVerify = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    def destTlsVerify = tlsVerify? "--dest-tls-verify=${tlsVerify}" : ''

    def fromImageRepo = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def fromImageUrl = "docker://${fromImageRepo}/${fromImage}:${fromTag}"

    def toImageRepo = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def toImgUrl = "docker://${toImageRepo}/${toImage}:${toTag}"

    return "skopeo copy ${srcCreds} ${destCreds} ${srcTlsVerify} ${destTlsVerify} ${fromImageUrl} ${toImgUrl}"
}

def tagImage(String env, String tokenVar, String image, String fromTag, String toTag) {
    return copyImage(env, tokenVar, image, fromTag, env, tokenVar, image, toTag)
}

def sshAgentBash(def sshKeyId, def ... commands) {
    return "ssh-agent bash -c 'ssh-add \${${sshKeyId}} ; ${commands.join('; ')}'"
}

def echo(Object... msgs) {
    msgs = msgs ? msgs.collect { "echo \"${it.toString()}\";" }.join(' ') : 'echo;'
    return "{ ${msgs} } 2> /dev/null"
}
