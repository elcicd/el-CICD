/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
 
@Field
def BANNER_SEPARATOR = loggingUtils.BANNER_SEPARATOR

def echoBanner(def ... msgs) {
    echo createBanner(msgs)
}

def shellEchoBanner(def ... msgs) {
    return "{ echo '${createBanner(msgs)}'; } 2> /dev/null"
}

def errorBanner(def ... msgs) {
    error(createBanner(msgs))
}

def createBanner(def ... msgs) {
    return """
        ${BANNER_SEPARATOR}

        ${msgFlatten(null, msgs).join("\n        ")}

        ${BANNER_SEPARATOR}
    """
}

def msgFlatten(def list, def msgs) {
    list = list ?: []
    if (!(msgs instanceof String) && !(msgs instanceof GString)) {
        msgs.each { msg ->
            list = msgFlatten(list, msg)
        }
    }
    else {
        list += msgs
    }

    return  list
}