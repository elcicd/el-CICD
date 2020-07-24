/*
 * Utility class defining the Jenkins agents.
 */
def call(Map args = [:], Closure body) {
    assert args.agent
    def agentImage = elCicdNodeDefs.images.get(args.agent) ?: args.agent

    def podLabel = args.agentName ?: args.agent

    podTemplate([
        label: "${podLabel}",
        cloud: 'openshift',
        workingDir: '/tmp',
        serviceAccount: 'jenkins',
        podRetention: onFailure(),
        idleMinutes: '30',
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: "${agentImage}",
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: '4Gi',
                resourceRequestCpu: '1',
                resourceLimitCpu: '4'
            )
        ]
    ]) {
        node(podLabel) {
            body()
        }
    }
}