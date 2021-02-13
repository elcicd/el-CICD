#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_bootstrap_el_cicd() {
    local EL_CICD_ONBOARDING_SERVER_TYPE=${1}

    if [[ -z "${ONBOARDING_MASTER_NAMESPACE}" ]]
    then
        echo "el-CICD ${EL_CICD_ONBOARDING_SERVER_TYPE} master project must be defined in ${ROOT_CONFIG_FILE}"
        echo "Set the value of ONBOARDING_MASTER_NAMESPACE ${ROOT_CONFIG_FILE} to and rerun."
        echo "Exiting."
        exit 1
    fi

    __gather_and_confirm_bootstrap_info_with_user

    if [[ $(_is_true ${INSTALL_KUBESEAL})  == ${_TRUE} ]]
    then
        _install_sealed_secrets
    fi

    if [[ ${UPDATE_JENKINS} == ${_YES} ]]
    then
        echo
        oc import-image jenkins -n openshift
    fi

    if [[ -z ${UPDATE_EL_CICD_JENKINS} || ${UPDATE_EL_CICD_JENKINS} == ${_YES} ]]
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

_source_el_cicd_meta_info_files() {
    echo
    echo 'WARNING: Each configuration file sourced will overwrite the last one in case of'
    echo '         conflicting variable definitions.'
    source ${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}

    local META_INFO_FILE=/tmp/_source_el_cicd_meta_info_files

    INCLUDE_FILES="${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}  $(echo ${INCLUDE_SYSTEM_FILES}:${INCLUDE_BOOTSTRAP_FILES} | tr ':' ' ')"
    __create_source_file ${META_INFO_FILE} "${INCLUDE_FILES}"

    # It's a hack, but ensures all files are read and realized properly if references to other variables are made later in file
    echo "SOURCING CONFIG FILES: ${ROOT_CONFIG_FILE} $(echo ${INCLUDE_SYSTEM_FILES}:${INCLUDE_BOOTSTRAP_FILES} | tr ':' ' ')"
    source ${META_INFO_FILE}
    source ${META_INFO_FILE}
}

_create_el_cicd_meta_info_config_map() {
    echo
    echo "Create ${EL_CICD_META_INFO_NAME} ConfigMap from ${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}"
    oc delete --ignore-not-found cm ${EL_CICD_META_INFO_NAME} -n ${ONBOARDING_MASTER_NAMESPACE}
    sleep 5

    local META_INFO_FILE=/tmp/_create_el_cicd_meta_info_config_map

    INCLUDE_FILES="${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE} $(echo ${INCLUDE_SYSTEM_FILES} | tr ':' ' ')"
    __create_source_file ${META_INFO_FILE} "${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE} ${INCLUDE_FILES}"

    echo "Source ${EL_CICD_META_INFO_NAME} ConfigMap Files: ${ROOT_CONFIG_FILE} ${INCLUDE_FILES}"
    oc create cm ${EL_CICD_META_INFO_NAME} --from-env-file=${META_INFO_FILE} -n ${ONBOARDING_MASTER_NAMESPACE}
}

__create_source_file() {
    local META_INFO_FILE=${1}
    local INCLUDE_FILES=${2}

    # iterates over each file an prints (default awk behavior) each unique line; thus, if second file contains the same first property
    # value, it skips it in the second file, and all is outpput to the tmp file for creating the final ConfigMap below
    local CURRENT_DIR=$(pwd)
    cd ${CONFIG_REPOSITORY_BOOTSTRAP}
    awk -F= '!line[$1]++' ../${ROOT_CONFIG_FILE} ${INCLUDE_FILES} > ${META_INFO_FILE}
    cd ${CURRENT_DIR}

    echo "CLUSTER_API_HOSTNAME=${CLUSTER_API_HOSTNAME}" >> ${META_INFO_FILE}
    sed -i -e 's/\s*$//' -e '/^$/d' -e '/^#.*$/d' ${META_INFO_FILE}
}

__gather_and_confirm_bootstrap_info_with_user() {
    _check_sealed_secrets

    echo
    UPDATE_JENKINS=$(__get_yes_no_answer 'Update cluster default Jenkins image? [Y/n] ')

    echo
    if [[ ! -z $(oc get is --ignore-not-found ${JENKINS_IMAGE_STREAM} -n openshift) ]]
    then
        UPDATE_EL_CICD_JENKINS=$(__get_yes_no_answer 'Update/build el-CICD Jenkins image? [Y/n] ')
    fi

    echo
    __summarize_and_confirm_bootstrap_run_with_user
}

__bootstrap_el_cicd_onboarding_server() {
    local EL_CICD_ONBOARDING_SERVER_TYPE=${1}

    local DEL_NAMESPACE=$(oc projects -q | grep ${ONBOARDING_MASTER_NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        __delete_master_namespace
    fi

    __create_master_namespace_with_selectors

    _create_el_cicd_meta_info_config_map

    if [[ ${EL_CICD_ONBOARDING_SERVER_TYPE} == 'non-prod' ]]
    then
        PIPELINE_TEMPLATES='non-prod-project-onboarding non-prod-project-delete'
    else
        PIPELINE_TEMPLATES='prod-project-onboarding'
    fi

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
            echo ${_YES}
        else
            echo ${_NO}
        fi
    else
        echo "SEALED SECRETS WILL NOT BE INSTALLED.  A Sealed Secrets version in el-CICD configuration is not defined."
    fi
    echo
    echo "Update cluster default Jenkins image? ${UPDATE_JENKINS}"

    if [[ ! -z ${UPDATE_EL_CICD_JENKINS} ]]
    then
        echo "Update/build el-CICD Jenkins image? ${UPDATE_EL_CICD_JENKINS}"
    else
        echo
        echo "WARNING: '${JENKINS_IMAGE_STREAM}' ImageStream was not found."
        echo 'el-CICD Jenkins WILL BE BUILT.'
    fi

    echo
    echo "Cluster API hostname? '${CLUSTER_API_HOSTNAME}'"
    echo "Cluster wildcard Domain? '*.${CLUSTER_WILDCARD_DOMAIN}'"

    local DEL_NAMESPACE=$(oc projects -q | grep ${ONBOARDING_MASTER_NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        echo
        echo -n "WARNING: '${ONBOARDING_MASTER_NAMESPACE}' was found, and will WILL BE DESTROYED AND REBUILT"
    else 
        echo
        echo -n "'${ONBOARDING_MASTER_NAMESPACE}' will be created for the el-CICD master namespace"
    fi

    if [[ ! -z ${ONBOARDING_MASTER_NODE_SELECTORS} ]]
    then
        echo -n " with the following node selectors:"
        echo "${ONBOARDING_MASTER_NODE_SELECTORS}"
    else
        echo
    fi

    if [[ $(_is_true ${JENKINS_SKIP_AGENT_BUILDS}) != ${_TRUE} && $(__base_jenkins_agent_exists) == ${_FALSE} ]]
    then
        echo
        echo "WARNING: JENKINS_SKIP_AGENT_BUILDS is not ${_TRUE}, and the base el-CICD Jenkins agent ImageStream was NOT found"
        echo
        echo "JENKINS AGENTS WILL BE BUILT"
    fi

    echo
    while read -e -t 0.1; do : ; done
    echo "Do you wish to continue? [${_YES}/${_NO}]: "
    CONTINUE='N'
    read CONTINUE
    if [[ ${CONTINUE} != ${_YES} ]]
    then
        echo "You must enter ${_YES} for bootstrap to continue.  Exiting..."
        exit 0
    fi
}

__create_master_namespace_with_selectors() {
    echo
    NODE_SELECTORS=$(echo ${ONBOARDING_MASTER_NAMESPACE} | tr -d '[:space:]')
    local CREATE_MSG="Creating ${ONBOARDING_MASTER_NAMESPACE}"
    if [[ ! -z  ${ONBOARDING_MASTER_NODE_SELECTORS} ]]
    then
        CREATE_MSG="${CREATE_MSG} with node selectors: ${ONBOARDING_MASTER_NODE_SELECTORS}"
    fi
    echo ${CREATE_MSG}

    if [[ ! -z ${ONBOARDING_MASTER_NODE_SELECTORS} ]]
    then
        oc adm new-project ${ONBOARDING_MASTER_NAMESPACE} --node-selector="${ONBOARDING_MASTER_NODE_SELECTORS}"
    else
        oc new-project ${ONBOARDING_MASTER_NAMESPACE}
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
                                  -e CASC_JENKINS_CONFIG=${JENKINS_CONTAINER_CONFIG_DIR}/${JENKINS_CASC_FILE} \
                                  -n ${ONBOARDING_MASTER_NAMESPACE}

    echo
    echo "Creating the Onboarding Automation Server pipelines:"
    for PIPELINE_TEMPLATE in ${PIPELINE_TEMPLATES[@]}
    do
        oc process -f ${BUILD_CONFIGS_DIR}/${PIPELINE_TEMPLATE}-pipeline-template.yml \
                   -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
                   -n ${ONBOARDING_MASTER_NAMESPACE} | \
            oc apply -f - -n ${ONBOARDING_MASTER_NAMESPACE}
    done

    local IS_NON_PROD='false'
    if [[ ${EL_CICD_ONBOARDING_SERVER_TYPE} == 'non-prod' ]]
    then
        IS_NON_PROD='true'
    fi
    oc process -f ${BUILD_CONFIGS_DIR}/refresh-credentials-pipeline-template.yml \
               -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
               -p IS_NON_PROD=${IS_NON_PROD} \
               -n ${ONBOARDING_MASTER_NAMESPACE} | \
        oc apply -f - -n ${ONBOARDING_MASTER_NAMESPACE}

    echo
    echo "======= BE AWARE: ONBOARDING REQUIRES CLUSTER ADMIN PERMISSIONS ======="
    echo
    echo "Be aware that the el-CICD Onboarding 'jenkins' service account needs cluster-admin"
    echo "permissions for managing and creating multiple cluster resources RBAC"
    echo
    echo "NOTE: This DOES NOT apply to CICD servers"
    echo
    oc adm policy add-cluster-role-to-user -z jenkins cluster-admin -n ${ONBOARDING_MASTER_NAMESPACE}
    echo
    echo "======= BE AWARE: ONBOARDING REQUIRES CLUSTER ADMIN PERMISSIONS ======="

    echo
    echo -n "Waiting for Jenkins to be ready."
    until
        sleep 3 && oc get pods --ignore-not-found -l name=jenkins -n ${ONBOARDING_MASTER_NAMESPACE} | grep "1/1"
    do
        echo -n '.'
    done

    echo
    echo 'Jenkins up, sleep for 10 more seconds to make sure server REST api is ready'
    sleep 10
}

__delete_master_namespace() {
    echo
    oc delete project ${ONBOARDING_MASTER_NAMESPACE}
    echo -n "Deleting ${ONBOARDING_MASTER_NAMESPACE} namespace"
    until
        !(oc project ${ONBOARDING_MASTER_NAMESPACE} > /dev/null 2>&1)
    do
        echo -n '.'
        sleep 1
    done
    echo
    echo "Namespace ${ONBOARDING_MASTER_NAMESPACE} deleted.  Sleep 10s to confirm cleanup."
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
            echo
            echo "Found ${FILE}; running..."
            eval "${FILE}"
            echo "Custom script ${FILE} completed"
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
        echo ${_YES}
    else
        echo ${_NO}
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