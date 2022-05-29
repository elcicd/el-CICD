#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export _TRUE='true'
export _FALSE='false'

export _YES='Yes'
export _NO='No'

_bootstrap_el_cicd() {
    EL_CICD_ONBOARDING_SERVER_TYPE=${1}

    if [[ -z "${ONBOARDING_MASTER_NAMESPACE}" ]]
    then
        echo "el-CICD ${EL_CICD_ONBOARDING_SERVER_TYPE} master project must be defined in ${ROOT_CONFIG_FILE}"
        echo "Set the value of ONBOARDING_MASTER_NAMESPACE ${ROOT_CONFIG_FILE} and rerun."
        echo "Exiting."
        exit 1
    fi

    __gather_and_confirm_bootstrap_info_with_user

    if [[ ${INSTALL_KUBESEAL} == ${_YES} ]]
    then
        _install_sealed_secrets
    fi

    if [[ -z ${UPDATE_EL_CICD_JENKINS} || ${UPDATE_EL_CICD_JENKINS} == ${_YES} ]]
    then
        _build_el_cicd_jenkins_image
        sleep 2
    fi

    __bootstrap_el_cicd_onboarding_server ${EL_CICD_ONBOARDING_SERVER_TYPE}

    echo
    echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS'
    _refresh_${EL_CICD_ONBOARDING_SERVER_TYPE/-/_}_credentials

    echo
    echo "RUN ALL CUSTOM SCRIPTS '${EL_CICD_ONBOARDING_SERVER_TYPE}-*.sh' FOUND IN ${CONFIG_REPOSITORY_BOOTSTRAP}"
    __run_custom_config_scripts

    __build_jenkins_agents_if_necessary

    echo
    echo "${EL_CICD_ONBOARDING_SERVER_TYPE} Onboarding Server Bootstrap Script Complete"
}

_source_el_cicd_config_files() {
    set -e 
    local META_INFO_FILE=/tmp/.el_cicd_config_file

    __create_config_source_file ${META_INFO_FILE} ${INCLUDE_BOOTSTRAP_FILES}

    source ${META_INFO_FILE}
    set +e +x
}

_create_el_cicd_meta_info_config_map() {
    set -e
    local META_INFO_FILE=/tmp/.el_cicd_meta_info_map_file

    __create_config_source_file ${META_INFO_FILE}

    oc delete --ignore-not-found cm ${EL_CICD_META_INFO_NAME} -n ${ONBOARDING_MASTER_NAMESPACE}
    sleep 3
    oc create cm ${EL_CICD_META_INFO_NAME} --from-env-file=${META_INFO_FILE} -n ${ONBOARDING_MASTER_NAMESPACE}
    set +e
}

__create_config_source_file() {
    local META_INFO_FILE=${1}
    local META_INFO_FILE_TMP=${1}_TMP
    local ADDITIONAL_FILES=${2}

    rm -f ${META_INFO_FILE} ${META_INFO_FILE_TMP}

    local EXTRA_CONF_FILES=$(echo ${INCLUDE_SYSTEM_FILES}:${ADDITIONAL_FILES} | tr ':' ' ')
    for CONF_FILE in ${ROOT_CONFIG_FILE} ${EXTRA_CONF_FILES}
    do
        local FOUND_FILES="${FOUND_FILES} $(find ${CONFIG_REPOSITORY} ${CONFIG_REPOSITORY_BOOTSTRAP} -maxdepth 1 -name ${CONF_FILE})"
    done
    echo "Config processed: $(basename ${SYSTEM_DEFAULT_CONFIG_FILE}) ${ROOT_CONFIG_FILE} ${EXTRA_CONF_FILES}"
    awk -F= '!line[$1]++' ${SYSTEM_DEFAULT_CONFIG_FILE} ${FOUND_FILES} >> ${META_INFO_FILE_TMP}

    echo "CLUSTER_API_HOSTNAME=${CLUSTER_API_HOSTNAME}" >> ${META_INFO_FILE_TMP}
    sed -i -e 's/\s*$//' -e '/^$/d' -e '/^#.*$/d' ${META_INFO_FILE_TMP}

    source ${META_INFO_FILE_TMP}
    cat ${META_INFO_FILE_TMP} | envsubst > ${META_INFO_FILE}

    sort -o ${META_INFO_FILE} ${META_INFO_FILE}

    rm ${META_INFO_FILE_TMP}
}
__gather_and_confirm_bootstrap_info_with_user() {
    _check_sealed_secrets

    if [[ ! -z $(oc get is --ignore-not-found ${JENKINS_IMAGE_STREAM} -n openshift) ]]
    then
        echo
        UPDATE_EL_CICD_JENKINS=$(_get_yes_no_answer 'Update/build el-CICD Jenkins image? [Y/n] ')
    fi

    __summarize_and_confirm_bootstrap_run_with_user
}

__bootstrap_el_cicd_onboarding_server() {
    local EL_CICD_ONBOARDING_SERVER_TYPE=${1}

    _delete_namespace ${ONBOARDING_MASTER_NAMESPACE} 10

    __create_master_namespace_with_selectors

    _create_el_cicd_meta_info_config_map

    if [[ ${EL_CICD_ONBOARDING_SERVER_TYPE} == 'non-prod' ]]
    then
        __create_onboarding_automation_server 'non-prod-project-onboarding'
    else
        __create_onboarding_automation_server 'prod-project-onboarding'
    fi
}

__summarize_and_confirm_bootstrap_run_with_user() {
    echo
    echo "SUMMARY:"
    echo
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo 'Please read CAREFULLY and verify this information is correct before proceeding.'
    echo 
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
         echo "Install Sealed Secrets version ${SEALED_SECRET_RELEASE_VERSION}? ${INSTALL_KUBESEAL}"
    else
        echo "SEALED SECRETS WILL NOT BE INSTALLED.  A Sealed Secrets version in el-CICD configuration is not defined."
    fi

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

    _confirm_continue
}

_confirm_continue() {
    echo
    echo -n "Do you wish to continue? [${_YES}/${_NO}]: "
    CONTINUE='N'
    read CONTINUE
    if [[ ${CONTINUE} != ${_YES} ]]
    then
        echo
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
    echo
    oc new-app jenkins-persistent -p MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
                                  -p VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
                                  -p DISABLE_ADMINISTRATIVE_MONITORS=${JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                  -p JENKINS_IMAGE_STREAM_TAG=${JENKINS_IMAGE_STREAM}:latest \
                                  -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                  -e OPENSHIFT_ENABLE_OAUTH=true \
                                  -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                  -e TRY_UPGRADE_IF_NO_MARKER=true \
                                  -e CASC_JENKINS_CONFIG=${JENKINS_CONTAINER_CONFIG_DIR}/${JENKINS_CASC_FILE} \
                                  -n ${ONBOARDING_MASTER_NAMESPACE}


    local IS_NON_PROD=$([ ${EL_CICD_ONBOARDING_SERVER_TYPE} == 'non-prod' ] && echo 'true' || echo 'false')

    sleep 2
    echo
    echo 'Waiting for Jenkins to come up...'
    oc rollout status dc jenkins -n ${ONBOARDING_MASTER_NAMESPACE}

    echo
    echo 'Jenkins up, sleep for 5 more seconds to make sure server REST api is ready'
    sleep 5

    echo
    echo "Creating the Onboarding Automation Server pipelines:"
    for PIPELINE_TEMPLATE in refresh-credentials delete-project ${EL_CICD_ONBOARDING_SERVER_TYPE}-project-onboarding
    do
        oc process --ignore-unknown-parameters \
                   -f ${BUILD_CONFIGS_DIR}/${PIPELINE_TEMPLATE}-pipeline-template.yml \
                   -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
                   -p IS_NON_PROD=${IS_NON_PROD} \
                   -n ${ONBOARDING_MASTER_NAMESPACE} | \
            oc apply -f - -n ${ONBOARDING_MASTER_NAMESPACE}
    done

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
}

_delete_namespace() {
    local NAMESPACE=$1
    local SLEEP_SEC=$2

    local DEL_NAMESPACE=$(oc projects -q | grep ${NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        echo
        oc delete project ${NAMESPACE}
        echo -n "Deleting ${NAMESPACE} namespace"
        until [[ !(oc project ${NAMESPACE} > /dev/null 2>&1) ]]
        do
            echo -n '.'
            sleep 1
        done

        echo
        if [[ ! -z ${SLEEP_SEC} ]]
        then
            echo "Namespace ${NAMESPACE} deleted.  Sleep ${SLEEP_SEC} second(s) to confirm."
            sleep ${SLEEP_SEC}
        fi
    fi
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

_get_yes_no_answer() {
    read -p "${1}" -n 1 USER_ANSWER
    >&2 echo

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