


def call(String command) {
    return """
        { set +x; } 2> /dev/null
        echo '${command}'
        ${command}
        set -x
    """
}