/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OCP resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

import groovy.transform.Field

@Field
def UNDEFINED = 'undefined'

@Field
def OKD_CONFIG_DIR = '.openshift'

def mergeMaps(def toMap, def fromMap) {
    if (toMap && fromMap) {
        fromMap.each { k, v -> toMap[k] = toMap[k] in Map ? mapMerge(toMap[k], v) : v }
    }

    return (toMap ?: fromMap)
}

def readTemplateDefs() {
    def templateDefs
    if (fileExists('template-defs.json')) {
        templateDefs = readJSON file: 'template-defs.json'
    }
    return templateDefs
}

def buildTemplatesAndGetParams(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    pipelineUtils.echoBanner("BUILD TEMPLATES AND RETRIEVE TEMPLATE DEFINITIONS")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/kustomization-template.yml", text: libraryResource('templates/kustomization-template.yml')

    microServices.each { microService ->
        dir("${microService.workDir}/${OKD_CONFIG_DIR}") {
            microService.templateDefs = readTemplateDefs()

            if (microService.templateDefs.templates) {
                microService.templateDefs.templates.eachWithIndex { templateDef, index ->
                    templateDef.appName = templateDef.appName ?: microService.name
                    templateDef.envPatchFile = templateDef[projectInfo.deployToEnv]?.patchFile ?: templateDef.patchFile
                    def patchName = templateDef.templateName ?: templateDef.file

                    templateDef.patchedFile = "patched-${templateDef.appName}-${index}.yml".toString()

                    buildTemplate(templateDef)

                    templateDef.params = mergeMaps(templateDef.params, templateDef[projectInfo.deployToEnv]?.params)
                }
            }
            else {
                ${shellEcho "No OpenShift templates found"}
            }
        }
    }
}

def buildTemplate(def templateDef) {
    def templateFileName = templateDef.file ?: "${templateDef.templateName}.yml"
    def templateFile = templateDef.file ?: "${el.cicd.OKD_TEMPLATES_DIR}/${templateFileName}"
    if (templateDef.envPatchFile) {
        def tempKustomizeDir = './kustomize-tmp'
        sh """
            ${shellEcho ''}
            ${shellEcho "Kustomizing ${templateDef.templateName} to ${templateDef.patchedFile} with patch: ${templateDef.envPatchFile}"}
            mkdir -p ${tempKustomizeDir}
            cp "${templateFile}" ${tempKustomizeDir}

            cp --parents ${templateDef.envPatchFile} ${tempKustomizeDir}

            cat ${el.cicd.TEMPLATES_DIR}/kustomization-template.yml | \
                sed -e 's|%TEMPLATE_FILE%|${templateFileName}|; s|%TEMPLATE_NAME%|${templateDef.templateName}|; s|%PATCH_FILE%|${templateDef.envPatchFile}|' > ${tempKustomizeDir}/kustomization.yml

            kustomize build ${tempKustomizeDir} > ${templateDef.patchedFile}

            cat ${templateDef.patchedFile}
            rm -rf ${tempKustomizeDir}
        """
    }
    else {
        sh """
            echo
            ${shellEcho "No patch found for:",
                        "appName: ${templateDef.appName}",
                        "template file: ${templateFile}"}
            cat ${templateFile} > ${templateDef.patchedFile}
        """
    }
}

def processTemplates(def projectInfo, def microServices, def imageTag) {
    assert projectInfo; assert microServices; assert imageTag

    pipelineUtils.echoBanner("APPLY OKD TEMPLATES AND RESOURCES")

    microServices.each { microService ->
        if (microService.templateDefs) {
            dir("${microService.workDir}/${OKD_CONFIG_DIR}") {
                microService.templateDefs.templates.eachWithIndex { templateDef, index ->
                    if (templateDef.params) {
                        templateDef.params.ROUTE_NAME = templateDef.params.ROUTE_NAME ?: templateDef.appName

                        if (!templateDef.params.ROUTE_HOST) {
                            def postfix = (projectInfo.deployToEnv != projectInfo.prodEnv) ? "-${projectInfo.deployToEnv}" : ''
                            templateDef.params.ROUTE_HOST = "${templateDef.appName}${postfix}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}".toString()
                        }
                    }

                    def paramsStr = templateDef.params.collect { key, value -> "-p '${key}=${value}'" }.join(' ')

                    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
                    def imageRepository = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_POSTFIX}"]
                    def pullSecret = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]

                    def fileName = templateDef.file ?: "${templateDef.templateName}.yml"
                    sh """
                        ${shellEcho '******',
                                    'PROCESSED AND APPLYING OCP TEMPLATE ' + templateDef.patchedFile,
                                    'PARAMS: ' + paramsStr,
                                    '******' }
                        mkdir -p ${projectInfo.deployToEnv}
                        oc process --local --ignore-unknown-parameters \
                            ${paramsStr} \
                            -p 'PROJECT_ID=${projectInfo.id}' \
                            -p 'MICROSERVICE_NAME=${microService.name}' \
                            -p 'APP_NAME=${templateDef.appName}' \
                            -p 'IMAGE_REPOSITORY=${imageRepository}' \
                            -p 'PULL_SECRET=${pullSecret}' \
                            -p 'ENV=${projectInfo.deployToEnv}' \
                            -p 'IMAGE_TAG=${imageTag}' \
                            -f ${templateDef.patchedFile} \
                            -o yaml > ./${projectInfo.deployToEnv}/processed-${index}-${fileName}
                    """
                }
            }
        }
        else {
            ${shellEcho 'No OpenShift deployment resource(s) found'}
        }
    }
}

def applyResources(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    microServices.each { microService ->
        dir("${microService.workDir}/${OKD_CONFIG_DIR}") {
            sh """
                mkdir -p default
                cp -n -v default/* ${projectInfo.deployToEnv} 2> /dev/null || ${shellEcho "No default OCP resources found"}

                cd ${projectInfo.deployToEnv}
                if [[ \$(ls *.{yml,yaml,json} 2> /dev/null | wc -l) -gt 0 ]]
                then
                    ${shellEcho '',
                                '******',
                                "APPLYING OCP RESOURCES FOR ${microService.name} IN PROJECT ${projectInfo.id}"}
                    IMAGE_PULL_BACKOFF_PODS=\$(oc get pods --no-headers -n ${projectInfo.deployToNamespace} | grep "${microService.name}-.*" | grep -i 'ImagePull')||:
                    if [[ ! -z "\${IMAGE_PULL_BACKOFF_PODS}" ]]
                    then
                        oc delete cronjob -l microservice=${microService.name} -n ${projectInfo.deployToNamespace}
                    fi

                    oc delete --cascade=false --wait dc -l microservice=${microService.name} -n ${projectInfo.deployToNamespace}
                    oc apply --overwrite --recursive -f . -n ${projectInfo.deployToNamespace}
                    ${shellEcho '******'}

                    ${shellEcho '',
                                '******',
                                "LABELING OCP RESOURCES FOR ${microService.name} IN PROJECT ${projectInfo.id}"}
                    oc label --overwrite --recursive -f . \
                        projectid=${projectInfo.id} \
                        microservice=${microService.name} \
                        deployment-commit-hash=${microService.deploymentCommitHash} \
                        release-version=${projectInfo.releaseVersionTag ?: UNDEFINED} \
                        deploy-time=\$(date +%d.%m.%Y-%H.%M.%S%Z) \
                        -n ${projectInfo.deployToNamespace}
                    ${shellEcho   '******'}
                else
                    ${shellEcho  'No OpenShift deployment resource(s) found'}
                fi
            """
        }
    }
}

def rolloutLatest(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')
    sh """
        ${pipelineUtils.shellEchoBanner("ROLLOUT LATEST IN ${projectInfo.deployToNamespace} FROM ARTIFACT REPOSITORY:", "${microServiceNames}")}

        for MICROSERVICE_NAME in ${microServiceNames}
        do
            DCS=\$(oc get dc -l microservice=\${MICROSERVICE_NAME} -o 'jsonpath={range .items[*]}{ .metadata.name }{" "}' -n ${projectInfo.deployToNamespace})
            if [[ ! -z "\${DCS}" ]]
            then
                for DC in \${DCS}
                do
                    ERROR_DEPLOYMENTS=\$(oc get pods --no-headers -n ${projectInfo.deployToNamespace} | grep "\${DC}-.*-deploy" | grep -vi ' Completed ' | awk '{print \$1}' | tr '\n' ' ')
                    if [[ ! -z \${ERROR_DEPLOYMENTS} ]]
                    then
                        oc delete pods \${ERROR_DEPLOYMENTS} -n ${projectInfo.deployToNamespace}
                        sleep 10
                    fi

                    DC="dc/\${DC}"
                    oc rollout latest \${DC} -n ${projectInfo.deployToNamespace}
                    sleep 3
                    # want to force it: first one probably didn't take if there was no image change
                    oc rollout latest \${DC} -n ${projectInfo.deployToNamespace} 2>&1 || :
                done
            else
                ${shellEcho   "******",
                              "No DeploymentConfigs found for \${MICROSERVICE_NAME}",
                              "******"}
            fi
        done
    """
}

def updateMicroServiceMetaInfo(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    microServices.each { microService ->
        def metaInfoCmName = "${projectInfo.id}-${microService.name}-${el.cicd.CM_META_INFO_POSTFIX}"

        sh """
            ${pipelineUtils.shellEchoBanner("UPDATE ${metaInfoCmName}")}

            oc delete --ignore-not-found cm ${metaInfoCmName} -n ${projectInfo.deployToNamespace}

            oc create cm ${metaInfoCmName} \
                --from-literal=projectid=${projectInfo.id} \
                --from-literal=microservice=${microService.name} \
                --from-literal=git-repo=${microService.gitRepoName} \
                --from-literal=src-commit-hash=${microService.srcCommitHash} \
                --from-literal=deployment-branch=${microService.deploymentBranch ?: UNDEFINED} \
                --from-literal=deployment-commit-hash=${microService.deploymentCommitHash} \
                --from-literal=release-version=${projectInfo.releaseVersionTag ?: UNDEFINED} \
                -n ${projectInfo.deployToNamespace}

            oc label cm ${metaInfoCmName} \
                projectid=${projectInfo.id} \
                microservice=${microService.name} \
                git-repo=${microService.gitRepoName} \
                src-commit-hash=${microService.srcCommitHash} \
                deployment-branch=${microService.deploymentBranch ?: UNDEFINED} \
                deployment-commit-hash=${microService.deploymentCommitHash} \
                release-version=${projectInfo.releaseVersionTag ?: UNDEFINED} \
                -n ${projectInfo.deployToNamespace}
        """
    }
}

def cleanupOrphanedResources(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    microServices.each { microService ->
        sh """
            ${pipelineUtils.shellEchoBanner("REMOVING ALL RESOURCES FOR ${microService.name} THAT ARE NOT PART OF DEPLOYMENT COMMIT ${microService.deploymentCommitHash}")}

            oc delete dc,svc,rc,hpa,configmaps,sealedsecrets,routes,cronjobs \
                -l microservice=${microService.name},deployment-commit-hash!=${microService.deploymentCommitHash} \
                -n ${projectInfo.deployToNamespace}
        """
    }
}

def removeAllMicroservices(def projectInfo) {
    assert projectInfo

    sh """
        ${pipelineUtils.shellEchoBanner("REMOVING ALL MICROSERVICES AND RESOURCES FROM ${projectInfo.deployToNamespace} FOR PROJECT ${projectInfo.id}")}

        oc delete dc,svc,rc,hpa,configmaps,sealedsecrets,routes,cronjobs -l microservice -n ${projectInfo.deployToNamespace}

        sleep 20
    """
}

def removeMicroservices(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')

    sh """
        ${pipelineUtils.shellEchoBanner("REMOVE SELECTED MICROSERVICES AND ALL ASSOCIATED RESOURCES FROM ${projectInfo.deployToNamespace}:", "${microServiceNames}")}

        for MICROSERVICE_NAME in ${microServiceNames}
        do
            oc delete dc,svc,rc,hpa,configmaps,sealedsecrets,routes,cronjobs -l microservice=\${MICROSERVICE_NAME} -n ${projectInfo.deployToNamespace}
        done

        sleep 10
    """
}
