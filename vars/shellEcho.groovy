

def call(String ... msgs) {
    msgs = msgs ? msgs.collect { "echo '${it}';" }.join(' ') : 'echo;'
    return "{ ${msgs} } 2> /dev/null"
}