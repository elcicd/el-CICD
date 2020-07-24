


def call(def sshkey, def ... commands) {
    return "ssh-agent bash -c 'ssh-add ${sshkey} ; ${commands.join('; ')}'"
}