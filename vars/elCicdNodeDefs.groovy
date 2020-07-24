/**
 * Map of agent name or code base to image.  Can be used to determine image to use based on label or code base in conjuction
 * with elCicdNode.
 */

class elCicdSlaveDefs {
    static def images = [base: 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-python:latest',
                         python: 'image-registry.openshift-image-registry.svc:5000/openshift/jenkins-agent-python:latest']
}