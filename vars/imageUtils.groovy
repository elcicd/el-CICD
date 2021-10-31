/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Image utilities
 */

import groovy.transform.Field

@Field
//"${fromUserNamePwd} ${toUserNamePwd} ${srcTlsVerify} ${destTlsVerify} ${fromImageUrl} ${toImgUrl}"
static def imageCopyCmd = 'skopeo copy --src-creds %s --dest-creds %s %s %s %s %s'

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
    def toImgUrl = "docker://${toImageRepo}/${microService.id}:${toTag}"

    def tlsVerify = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    def srcTlsVerify = tlsVerify? "--src-tls-verify=${tlsVerify}" : ''

    tlsVerify = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"]
    def destTlsVerify = tlsVerify? "--dest-tls-verify=${tlsVerify}" : ''

    def fromUserNamePwd = el.cicd["${fromEnv}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"] + ":\${${fromTokenVar}}"
    def toUserNamePwd = el.cicd["${toEnv}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"] + ":\${${toTokenVar}}"

    return String.format(imageCopyCmd, fromUserNamePwd, toUserNamePwd, srcTlsVerify, destTlsVerify, fromImageUrl, toImgUrl).toString()
 }

 def tagImageCmd(String env,
                 String tokenVar,
                 String image,
                 String fromTag,
                 String toTag) {
    return copyImageCmd(env, tokenVar, image, fromTag, token, env, tokenVar, image, toTag)
 }
