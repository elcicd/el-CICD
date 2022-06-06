/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for creating curl commands.
 */

import groovy.transform.Field

@Field
def DELETE = 'DELETE'

@Field
def GET = 'GET'

@Field
def PATCH = 'PATCH'

@Field
def POST = 'POST'

@Field
def PUT = 'PUT'

@Field
def XML_CONTEXT_HEADER = "-H 'Content-Type:text/xml'"

@Field
def JSON_CONTEXT_HEADER = '-H application:json'

@Field
def SILENT = '-o /dev/null'

@Field
def FAIL_SILENT = '-f 2>/dev/null'

def getCmd(def httpVerb, String tokenName, def silent = true) {    
    return """curl -ksS -X ${httpVerb} ${silent ? SILENT : ''} -H "Authorization: Bearer \${${tokenName}}" """
}