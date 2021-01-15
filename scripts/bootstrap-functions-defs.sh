#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_bootstrap_el_cicd() {
    PIPELINE_TEMPLATES=${1}

    _check_sealed_secrets
    __confirm_update_default_jenkins_image
    __confirm_build_el_cicd_jenkins_image

    _summarize_and_confirm_bootstrap_run_with_user

    if [[ ${INSTALL_KUBESEAL} == 'Yes' ]]
    then
        _install_sealed_secrets
    fi

    if [[ ${UPDATE_JENKINS} == 'Yes' ]]
    then
        oc import-image jenkins -n openshift
    fi

    if [[ ${UPDATE_EL_CICD_JENKINS} == 'Yes' ]]
    then
        _build_el_cicd_jenkins_image
    fi

    local DEL_NAMESPACE=$(oc projects -q | grep ${EL_CICD_MASTER_NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        _delete_master_namespace
    fi

    _create_master_namespace_with_selectors

    __create_onboarding_automation_server "${PIPELINE_TEMPLATES}"
}

__confirm_update_default_jenkins_image() {
    UPDATE_JENKINS='N'
    echo -n 'Update cluster default Jenkins image? [Y/n] '
    read -n 1 UPDATE_JENKINS
    echo

    if [[ ${UPDATE_JENKINS} == 'Y' ]]
    then
        UPDATE_JENKINS='Yes'
    else
        UPDATE_JENKINS='No'
    fi
}

__confirm_build_el_cicd_jenkins_image() {
    UPDATE_EL_CICD_JENKINS='N'
    echo -n 'Update/build el-CICD Jenkins image? [Y/n] '
    read -n 1 UPDATE_EL_CICD_JENKINS
    echo

    if [[ ${UPDATE_EL_CICD_JENKINS} == 'Y' ]]
    then
        UPDATE_EL_CICD_JENKINS='Yes'
    else
        UPDATE_EL_CICD_JENKINS='No'
    fi
}

_summarize_and_confirm_bootstrap_run_with_user() {
    echo
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo 'Please read CAREFULLY and verify this information is correct before proceeding.'
    echo 
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
        echo "Install/Update Sealed Secrets to version? ${INSTALL_KUBESEAL}"
    else
        echo "SEALED SECRETS WILL NOT BE INSTALLED.  A Sealed Secrets version in el-CICD configuration is not defined."
    fi
    echo
    echo "Update cluster default Jenkins image? ${UPDATE_JENKINS}"
    echo "Update/build el-CICD Jenkins image? ${UPDATE_EL_CICD_JENKINS}"
    echo
    echo "Cluster wildcard Domain? '*.${CLUSTER_WILDCARD_DOMAIN}'"

    local DEL_NAMESPACE=$(oc projects -q | grep ${EL_CICD_MASTER_NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        echo
        echo -n "WARNING: '${EL_CICD_MASTER_NAMESPACE}' was found, and will WILL BE DESTROYED AND REBUILT"
    else 
        echo
        echo -n "el-CICD Master namespace to be create: '${EL_CICD_MASTER_NAMESPACE}'"
    fi
    if [[ ! -z ${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS} ]]
    then
        echo " with the following node selectors:"
        echo "${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS}"
    else
        echo
    fi

    echo
    echo "Do you wish to continue? [Yes/No]: "
    CONTINUE='N'
    read CONTINUE
    if [[ ${CONTINUE} != 'Yes' ]]
    then
        echo "You must enter 'Yes' for bootstrap to continue.  Exiting..."
        exit 0
    fi
}

_create_master_namespace_with_selectors() {
    echo
    NODE_SELECTORS=$(echo ${EL_CICD_MASTER_NAMESPACE} | tr -d '[:space:]')
    echo "Creating ${EL_CICD_MASTER_NAMESPACE} with node selectors: ${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS}"
    oc adm new-project ${EL_CICD_MASTER_NAMESPACE} --node-selector="${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS}"
}

_build_el_cicd_jenkins_image() {
    echo
    echo "Updating el-CICD Jenkins image ${JENKINS_IMAGE_STREAM}"
    echo
    if [[ ! -n $(oc get bc ${JENKINS_IMAGE_STREAM} --ignore-not-found -n openshift) ]]
    then
        oc new-build --name ${JENKINS_IMAGE_STREAM} --binary=true --strategy=docker -n openshift
    fi

    cp ${TEMPLATES_DIR}/Dockerfile.jenkins-template ${CONFIG_REPOSITORY_JENKINS}/Dockerfile
    sed -i -e "s|%OCP_IMAGE_REPO%|${OCP_IMAGE_REPO}|;" \
           -e  "s|%CONFIG_PATH%|${EL_CICD_JENKINS_CONTAINER_CONFIG_DIR}|g;" \
           -e  "s/%JENKINS_CONFIGURATION_FILE%/${JENKINS_CASC_FILE}/g;" \
           -e  "s/%JENKINS_PLUGINS_FILE%/${JENKINS_PLUGINS_FILE}/g" \
        ${CONFIG_REPOSITORY_JENKINS}/Dockerfile

    oc start-build ${JENKINS_IMAGE_STREAM} --from-dir=${CONFIG_REPOSITORY_JENKINS} --wait --follow -n openshift
    rm -f ${CONFIG_REPOSITORY_JENKINS}/Dockerfile
}

__create_onboarding_automation_server() {
    PIPELINE_TEMPLATES=${1}

    echo
    oc new-app jenkins-persistent -p MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
                                  -p VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
                                  -p DISABLE_ADMINISTRATIVE_MONITORS=${JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                  -p JENKINS_IMAGE_STREAM_TAG=${JENKINS_IMAGE_STREAM}:latest \
                                  -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                  -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                  -e TRY_UPGRADE_IF_NO_MARKER=true \
                                  -e CASC_JENKINS_CONFIG=${EL_CICD_JENKINS_CONTAINER_CONFIG_DIR}/${JENKINS_CASC_FILE} \
                                  -n ${EL_CICD_MASTER_NAMESPACE}

    echo
    echo "Creating the Onboarding Automation Server pipelines:"
    for PIPELINE_TEMPLATE in ${PIPELINE_TEMPLATES[@]}
    do
        oc process -f ${BUILD_CONFIGS_DIR}/${PIPELINE_TEMPLATE}-pipeline-template.yml \
                -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} -n ${EL_CICD_MASTER_NAMESPACE} | \
            oc apply -f - -n ${EL_CICD_MASTER_NAMESPACE}
    done

    echo
    echo "'jenkins' service account needs cluster-admin permissions for managing multiple projects and permissions"
    oc adm policy add-cluster-role-to-user -z jenkins cluster-admin -n ${EL_CICD_MASTER_NAMESPACE}

    echo
    echo -n "Waiting for Jenkins to be ready."
    until
        sleep 3 && oc get pods --ignore-not-found -l name=jenkins -n ${EL_CICD_MASTER_NAMESPACE} | grep "1/1"
    do
        echo -n '.'
    done

    echo
    echo 'Jenkins up, sleep for 10 more seconds to make sure server REST api is ready'
    sleep 10
}

_delete_master_namespace() {
    echo
    oc delete project ${EL_CICD_MASTER_NAMESPACE}
    echo -n "Deleting ${EL_CICD_MASTER_NAMESPACE} namespace"
    until
        !(oc project ${EL_CICD_MASTER_NAMESPACE} > /dev/null 2>&1)
    do
        echo -n '.'
        sleep 1
    done
    echo
    echo "Namespace ${EL_CICD_MASTER_NAMESPACE} deleted.  Sleep 10s to confirm cleanup."
    sleep 10
}

_build_jenkins_agents() {
    local NAMESPACE_NAME=${1}
    # BUILD_BASE_ONLY must be 'true' if the base agent is not to be built
    local BUILD_BASE_ONLY=${2}

    if [[ ${JENKINS_SKIP_AGENT_BUILDS} != 'true' ]]
    then
        HAS_BASE_AGENT=$(oc get --ignore-not-found is jenkins-agent-el-cicd-${JENKINS_AGENT_DEFAULT} -n openshift -o jsonpath='{.metadata.name}')
        if [[ -z ${HAS_BASE_AGENT} ]]
        then
            echo
            echo "Creating Jenkins Agents"
            oc start-build create-all-jenkins-agents -e BUILD_BASE_ONLY=${BUILD_BASE_ONLY} -n ${NAMESPACE_NAME}
            echo "Started 'create-all-jenkins-agents' job on Non-prod Onboarding Automation Server"
        else 
            echo
            echo "Base agent found: to manually rebuild Jenkins Agents, run the 'create-all-jenkins-agents' job"
        fi
    else
        echo
        echo "JENKINS_SKIP_AGENT_BUILDS=true.  Jenkins agent builds skipped."
    fi
}

_run_custom_config_scripts() {
    SERVER_TYPE=${1}

    local SCRIPTS=$(find "${CONFIG_REPOSITORY_BOOTSTRAP}" -type f -executable \( -name "${SERVER_TYPE}-*.sh" -o -name 'all-*.sh' \) | sort | tr '\n' ' ')
    if [[ ! -z ${SCRIPTS} ]]
    then
        for FILE in ${SCRIPTS}
        do
            echo "Running custom script $(basename ${FILE})" 
            eval "${FILE}"
        done
    else
        echo 'No custom config scripts found...'
    fi
}