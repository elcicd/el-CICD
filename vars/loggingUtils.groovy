/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Pipeline logging utilities
 */
 
def spacedEcho(def msg) {
    echo "\n${msg}\n"
}

def echoBanner(def... msgs) {
    echo createBanner(msgs)
}

def shellEchoBanner(def... msgs) {
    return "{ echo '${createBanner(msgs)}'; } 2> /dev/null"
}

def errorBanner(def... msgs) {
    error(createBanner(msgs))
}

def createBanner(def... msgs) {
    return """
        ===========================================

        ${msgFlatten(null, msgs).join("\n        ")}

        ===========================================
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