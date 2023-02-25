/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Generates shell commands from parameters.
 */

/* When executed, command will echo either the image name and sha, or null if image doesn't exist. */
def verifyImage(String env, String username, String password, String image, String tag) {
    def creds = "--creds \${${username}}:\${${password}}"

    def tlsVerify = el.cicd["${env}${el.cicd.IMAGE_REGISTRY_ENABLE_TLS_POSTFIX}"]
    tlsVerify = tlsVerify ? "--tls-verify=${tlsVerify}" : ''

    def imageRepo = el.cicd["${env}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def imageUrl = "docker://${imageRepo}/${image}:${tag}"

    return "skopeo inspect --format '{{.Name}}({{.Digest}})' ${tlsVerify} ${creds} ${imageUrl} 2> /dev/null || :"
}

def copyImage(String fromEnv, String fromUsername, String fromPwd, String fromImage, String fromTag,
              String toEnv, String toUsername, String toPwd, String toImage, String toTag)
{
    def fromCreds = "--src-creds \${${fromUsername}}:\${${fromPwd}}"
    def toCreds = "--dest-creds \${${toUsername}}:\${${toPwd}}"

    def tlsVerify = el.cicd["${fromEnv}${el.cicd.IMAGE_REGISTRY_ENABLE_TLS_POSTFIX}"]
    def fromTlsVerify = tlsVerify ? "--src-tls-verify=${tlsVerify}" : ''

    tlsVerify = el.cicd["${toEnv}${el.cicd.IMAGE_REGISTRY_ENABLE_TLS_POSTFIX}"]
    def toTlsVerify = tlsVerify ? "--dest-tls-verify=${tlsVerify}" : ''

    def fromImageRepo = el.cicd["${fromEnv}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def fromImageUrl = "docker://${fromImageRepo}/${fromImage}:${fromTag}"

    def toImageRepo = el.cicd["${toEnv}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
    def toImgUrl = "docker://${toImageRepo}/${toImage}:${toTag}"

    return "skopeo copy ${fromCreds} ${toCreds} ${fromTlsVerify} ${toTlsVerify} ${fromImageUrl} ${toImgUrl}"
}

def tagImage(String env, String username, String pwd, String image, String fromTag, String toTag) {
    return copyImage(env, username, pwd, image, fromTag, env, username, pwd, image, toTag)
}

def sshAgentBash(def sshKeyId, def ... commands) {
    return "ssh-agent bash -c 'ssh-add \${${sshKeyId}} ; ${commands.join('; ')}'"
}

def echo(Object... msgs) {
    println '++++++++++++++'
    println msgs.toString()
    println '++++++++++++++'
    
    def out = ''
    msgs.each { msg ->
        out += "echo \"${msg}\"; "
    }
    return "{ ${out} } 2> /dev/null"
}
