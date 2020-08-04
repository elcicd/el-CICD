/*
 * Utility class defining the Jenkins agents.
 */


@groovy.transform.Field
agentDefs = [base: 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-python:latest',
             python: 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-python:latest']
 
def call(Map args = [:], Closure body) {
    assert args.agent
    def agentImage = agentDefs[args.agent] ?: args.agent

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