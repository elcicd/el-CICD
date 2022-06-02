/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for creating curl commands.
 */

@Field
def GET = 'GET'

@Field
def POST = 'POST'

@Field
def DELETE = 'DELETE'

@Field
def XML_CONTEXT_HEADER = "-H 'Content-Type:text/xml'"

@Field
def SILENT = '-o /dev/null'

@Field
def FAIL_SILENT = '-f 2>/dev/null'

def getCmd(def httpVerb, def token, def silent = false) {    
    return """curl -ksS -X ${httpVerb} ${silent ? SILENT : ''} -H "Authorization: Bearer \${${token}}" """
}