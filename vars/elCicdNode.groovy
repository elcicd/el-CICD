/*
 * Utility class defining the Jenkins agents.
 */


@groovy.transform.Field
agentDefs = [base: 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-python:latest',
             'java-maven': 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-java-maven:latest',
             'r-lang': 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-r-lang:latest',
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
                resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
                resourceRequestCpu: '1',
                resourceLimitCpu: "${el.cicd.JENKINS_AGENT_CPU_LIMIT}"
            )
        ]
    ]) {
        node(podLabel) {
            body()
        }
    }
}