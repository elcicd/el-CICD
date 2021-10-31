/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Image utilities
 */

def inspectImageCmd(String env, String tokenVar, String image, String tag) {
    def imageRepo = el.cicd["${env}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def imageUrl = "docker://${imageRepo}/${image}:${tag}"

    def tlsVerify = el.cicd["${env}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    tlsVerify = tlsVerify? "--src-tls-verify=${tlsVerify}" : ''

    def user = el.cicd["${env}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
    def creds = "--creds ${user}:\${${tokenVar}}"

    return "skopeo inspect --raw ${tlsVerify} ${creds} ${imageUrl} || :"
}

def copyImageCmd(String fromEnv,
                 String fromTokenVar,
                 String fromImage,
                 String fromTag,
                 String toEnv,
                 String toTokenVar,
                 String toImage,
                 String toTag) {
    def fromImageRepo = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def fromImageUrl = "docker://${fromImageRepo}/${fromImage}:${fromTag}"

    def toImageRepo = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_POSTFIX}"]
    def toImgUrl = "docker://${toImageRepo}/${toImage}:${toTag}"

    def tlsVerify = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    def srcTlsVerify = tlsVerify? "--src-tls-verify=${tlsVerify}" : ''

    tlsVerify = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    def destTlsVerify = tlsVerify? "--dest-tls-verify=${tlsVerify}" : ''

    def user = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
    def srcCreds = "--creds ${user}:\${${fromTokenVar}}"

    user = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
    def destCreds = "--creds ${user}:\${${toTokenVar}}"

    return "skopeo copy ${srcCreds} ${destCreds} ${srcTlsVerify} ${destTlsVerify} ${fromImageUrl} ${toImgUrl}"
 }

 def tagImageCmd(String env,
                 String tokenVar,
                 String image,
                 String fromTag,
                 String toTag) {
    return copyImageCmd(env, tokenVar, image, fromTag, env, tokenVar, image, toTag)
 }
