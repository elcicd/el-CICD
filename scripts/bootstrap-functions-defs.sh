#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_bootstrap_el_cicd() {
    local EL_CICD_ONBOARDING_SERVER_TYPE=${1}

    if [[ -z "${EL_CICD_MASTER_NAMESPACE}" ]]
    then
        echo "el-CICD ${EL_CICD_ONBOARDING_SERVER_TYPE} master project must be defined in ${EL_CICD_SYSTEM_CONFIG_FILE}"
        echo "Set the value of EL_CICD_MASTER_NAMESPACE ${EL_CICD_SYSTEM_CONFIG_FILE} to and rerun."
        echo "Exiting."
        exit 1
    fi

    __gather_and_confirm_bootstrap_info_with_user

    if [[ $(is_true ${INSTALL_KUBESEAL})  == ${_TRUE} ]]
    then
        _install_sealed_secrets
    fi

    if [[ ${UPDATE_JENKINS} == 'Yes' ]]
    then
        echo
        oc import-image jenkins -n openshift
    fi

    if [[ ${UPDATE_EL_CICD_JENKINS} == 'Yes' ]]
    then
        _build_el_cicd_jenkins_image
    fi

    __bootstrap_el_cicd_onboarding_server ${EL_CICD_ONBOARDING_SERVER_TYPE}

    echo
    echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS'
    ${SCRIPTS_DIR}/el-cicd-${EL_CICD_ONBOARDING_SERVER_TYPE}-credentials.sh

    echo
    echo "RUN ALL CUSTOM SCRIPTS '${EL_CICD_ONBOARDING_SERVER_TYPE}-*.sh' FOUND IN ${CONFIG_REPOSITORY_BOOTSTRAP}"
    __run_custom_config_scripts

    __build_jenkins_agents_if_necessary

    echo
    echo "${EL_CICD_ONBOARDING_SERVER_TYPE} Onboarding Server Bootstrap Script Complete"
}

_create_el_cicd_meta_info_config_map() {
    echo
    echo "Create ${EL_CICD_META_INFO_NAME} ConfigMap from ${CONFIG_REPOSITORY}/${EL_CICD_SYSTEM_CONFIG_FILE}"
    oc delete --ignore-not-found cm ${EL_CICD_META_INFO_NAME}
    sleep 5
    sed -e 's/\s*$//' ${CONFIG_REPOSITORY}/${EL_CICD_SYSTEM_CONFIG_FILE} > /tmp/${EL_CICD_SYSTEM_CONFIG_FILE}

    oc create cm ${EL_CICD_META_INFO_NAME} --from-env-file=/tmp/${EL_CICD_SYSTEM_CONFIG_FILE} -n ${EL_CICD_MASTER_NAMESPACE}

    rm /tmp/${EL_CICD_SYSTEM_CONFIG_FILE}
}

__gather_and_confirm_bootstrap_info_with_user() {
    _check_sealed_secrets

    echo
    UPDATE_JENKINS=$(__get_yes_no_answer 'Update cluster default Jenkins image? [Y/n] ')
    echo
    UPDATE_EL_CICD_JENKINS=$(__get_yes_no_answer 'Update/build el-CICD Jenkins image? [Y/n] ')

    echo
    __summarize_and_confirm_bootstrap_run_with_user
}

__bootstrap_el_cicd_onboarding_server() {
    local EL_CICD_ONBOARDING_SERVER_TYPE=${1}

    local DEL_NAMESPACE=$(oc projects -q | grep ${EL_CICD_MASTER_NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        __delete_master_namespace
    fi

    __create_master_namespace_with_selectors

    if [[ ${EL_CICD_ONBOARDING_SERVER_TYPE} == 'non-prod' ]]
    then
        PIPELINE_TEMPLATES='non-prod-project-onboarding non-prod-project-delete'
    else
        PIPELINE_TEMPLATES='prod-project-onboarding'
    fi

    PIPELINE_TEMPLATES="${PIPELINE_TEMPLATES} refresh-credentials"

    __create_onboarding_automation_server "${PIPELINE_TEMPLATES}"
}

__summarize_and_confirm_bootstrap_run_with_user() {
    echo
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo 'Please read CAREFULLY and verify this information is correct before proceeding.'
    echo 
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
         echo -n "Install Sealed Secrets version ${SEALED_SECRET_RELEASE_VERSION}? "
        if [[ $(_is_true ${INSTALL_KUBESEAL})  == ${_TRUE} ]]
        then
            echo 'Yes'
        else
            echo 'No'
        fi
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
        echo -n "'${EL_CICD_MASTER_NAMESPACE}' will be created for the el-CICD master namespace"
    fi

    if [[ ! -z ${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS} ]]
    then
        echo -n " with the following node selectors:"
        echo "${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS}"
    else
        echo
    fi

    if [[ $(_is_true ${JENKINS_SKIP_AGENT_BUILDS}) != ${_TRUE} && $(__base_jenkins_agent_exists) == ${_FALSE} ]]
    then
        echo
        echo "WARNING: JENKINS_SKIP_AGENT_BUILDS is not ${_TRUE}, and no el-CICD Jenkins agent ImageStreams were found"
        echo
        echo "JENKINS AGENTS WILL BE BUILT"
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

__create_master_namespace_with_selectors() {
    echo
    NODE_SELECTORS=$(echo ${EL_CICD_MASTER_NAMESPACE} | tr -d '[:space:]')
    local CREATE_MSG="Creating ${EL_CICD_MASTER_NAMESPACE}"
    if [[ ! -z  ${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS} ]]
    then
        CREATE_MSG=" with node selectors: ${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS}"
    fi
    echo ${CREATE_MSG}

    if [[ ! -z ${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS} ]]
    then
        oc adm new-project ${EL_CICD_MASTER_NAMESPACE} --node-selector="${EL_CICD_MASTER_NAMESPACE_NODE_SELECTORS}"
    else
        oc new-project ${EL_CICD_MASTER_NAMESPACE}
    fi
}

__create_onboarding_automation_server() {
    local PIPELINE_TEMPLATES=${1}

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

__delete_master_namespace() {
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

__build_jenkins_agents_if_necessary() {
    local HAS_BASE_AGENT=$(oc get --ignore-not-found is ${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT} -n openshift -o jsonpath='{.metadata.name}')
    if [[ -z ${HAS_BASE_AGENT} ]]
    then
        _build_el_cicd_jenkins_agent_images_image
    else
        echo
        echo "Base agent found: to manually rebuild Jenkins Agents, run the 'el-cicd.sh --jenkins-agents'"
    fi
}

__run_custom_config_scripts() {
    local SCRIPTS=$(find "${CONFIG_REPOSITORY_BOOTSTRAP}" -type f -executable \( -name "${EL_CICD_ONBOARDING_SERVER_TYPE}-*.sh" -o -name 'all-*.sh' \) | sort | tr '\n' ' ')
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

__base_jenkins_agent_exists() {
    local HAS_BASE_AGENT=$(oc get --ignore-not-found is ${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT} -n openshift -o jsonpath='{.metadata.name}')
    if [[ -z ${HAS_BASE_AGENT} ]]
    then
        echo ${_FALSE}
    else
        echo ${_TRUE}
    fi
}

__get_yes_no_answer() {
    read -p "${1}" -n 1 USER_ANSWER

    if [[ ${USER_ANSWER} == 'Y' ]]
    then
        echo 'Yes'
    else
        echo 'No'
    fi
}

_compare_ignore_case_and_extra_whitespace() {
    local FIRST=$(echo "${1}" | xargs)
    local SECOND=$(echo "${2}" | xargs)
    if [[ -z $(echo "${FIRST}" | grep --ignore-case "^${SECOND}$") ]]
    then
        echo ${_FALSE}
    else
        echo ${_TRUE}
    fi
}

_is_true() {
    _compare_ignore_case_and_extra_whitespace "${1}" ${_TRUE}
}