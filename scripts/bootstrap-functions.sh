#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export _TRUE='true'
export _FALSE='false'

export _YES='Yes'
export _NO='No'

_bootstrap_el_cicd() {
    ONBOARDING_SERVER_TYPE=${1}

    if [[ -z "${ONBOARDING_MASTER_NAMESPACE}" ]]
    then
        echo "el-CICD ${ONBOARDING_SERVER_TYPE} master project must be defined in ${ROOT_CONFIG_FILE}"
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

    __build_jenkins_agents_if_necessary

    __bootstrap_el_cicd_onboarding_server

    echo
    echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS'
    _refresh_${ONBOARDING_SERVER_TYPE/-/_}_credentials

    echo
    echo "RUN ALL CUSTOM SCRIPTS '${ONBOARDING_SERVER_TYPE}-*.sh' FOUND IN ${EL_CICD_CONFIG_BOOTSTRAP_DIR}"
    __run_custom_config_scripts

    echo
    echo "${ONBOARDING_SERVER_TYPE} Onboarding Server Bootstrap Script Complete:"
    echo "https://${JENKINS_URL}"
}

_create_meta_info_file() {
    META_INFO_FILE=/tmp/.el_cicd_meta_info_file
    local META_INFO_FILE_TMP=.el_cicd_meta_info_tmp_file
    local ADDITIONAL_FILES=${1}

    rm -f ${META_INFO_FILE} ${META_INFO_FILE_TMP}

    local EXTRA_CONF_FILES=$(echo ${INCLUDE_SYSTEM_FILES}:${ADDITIONAL_FILES} | tr ':' ' ')
    for CONF_FILE in ${ROOT_CONFIG_FILE} ${EXTRA_CONF_FILES}
    do
        local FOUND_FILES="${FOUND_FILES} $(find ${EL_CICD_CONFIG_DIR} ${EL_CICD_CONFIG_BOOTSTRAP_DIR} -maxdepth 1 -name ${CONF_FILE})"
    done
    awk -F= '!line[$1]++' ${SYSTEM_DEFAULT_CONFIG_FILE} ${FOUND_FILES} >> ${META_INFO_FILE_TMP}

    echo "CLUSTER_API_HOSTNAME=${CLUSTER_API_HOSTNAME}" >> ${META_INFO_FILE_TMP}
    sed -i -e 's/\s*$//' -e '/^$/d' -e '/^#.*$/d' ${META_INFO_FILE_TMP}

    source ${META_INFO_FILE_TMP}
    cat ${META_INFO_FILE_TMP} | envsubst > ${META_INFO_FILE}
    
    rm ${META_INFO_FILE_TMP}

    sort -o ${META_INFO_FILE} ${META_INFO_FILE}
    echo
    echo "Config files processed for el-cicd-meta-info (${META_INFO_FILE}):"
    echo "    $(basename ${SYSTEM_DEFAULT_CONFIG_FILE}) ${ROOT_CONFIG_FILE} ${EXTRA_CONF_FILES}"
}

__gather_and_confirm_bootstrap_info_with_user() {
    _check_sealed_secrets

    if [[ ! -z $(oc get is --ignore-not-found ${JENKINS_IMAGE_NAME} -n openshift) ]]
    then
        echo
        UPDATE_EL_CICD_JENKINS=$(_get_yes_no_answer 'Update/build el-CICD Jenkins image? [Y/n] ')
    fi

    __summarize_and_confirm_bootstrap_run_with_user
}

__bootstrap_el_cicd_onboarding_server() {
    echo
    echo "======= BE AWARE: ONBOARDING REQUIRES CLUSTER ADMIN PERMISSIONS ======="
    echo
    echo "Be aware that the el-CICD Onboarding 'jenkins' service account needs cluster-admin"
    echo "permissions for managing and creating multiple cluster resources RBAC"
    echo
    echo "NOTE: This DOES NOT apply to CICD servers"
    echo
    echo "======= BE AWARE: ONBOARDING REQUIRES CLUSTER ADMIN PERMISSIONS ======="
    echo
    
    if [[ -z $(oc get project ${ONBOARDING_MASTER_NAMESPACE} -o name --no-headers --ignore-not-found)  ]]
    then
        oc new-project ${ONBOARDING_MASTER_NAMESPACE}
    fi
    sleep 2
    
    __create_onboarding_automation_server
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
        echo "WARNING: '${JENKINS_IMAGE_NAME}' ImageStream was not found."
        echo 'el-CICD Jenkins WILL BE BUILT.'
    fi

    echo
    echo "Cluster API hostname? '${CLUSTER_API_HOSTNAME}'"
    echo "Cluster wildcard Domain? '*.${CLUSTER_WILDCARD_DOMAIN}'"

    local NAMESPACE_EXISTS=$(oc projects -q | grep ${ONBOARDING_MASTER_NAMESPACE} | tr -d '[:space:]')
    if [[ ! -z "${NAMESPACE_EXISTS}" ]]
    then
        echo
        echo -n "'${ONBOARDING_MASTER_NAMESPACE}' was found, and the onboarding server environment will be reinstalled"
    else 
        echo
        echo -n "'${ONBOARDING_MASTER_NAMESPACE}' was not found, and the onboarding server environment will be created and installed"
    fi

    if [[ $(_is_true ${JENKINS_SKIP_AGENT_BUILDS}) != ${_TRUE} && $(__base_jenkins_agent_exists) == ${_FALSE} ]]
    then
        echo
        echo "WARNING: JENKINS_SKIP_AGENT_BUILDS is not ${_TRUE}, and the base el-CICD Jenkins agent image was NOT found"
        echo
        echo "ALL JENKINS AGENTS WILL BE BUILT"
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

__create_onboarding_automation_server() {    
    echo
    set -e    
    if [[ ${OKD_VERSION} ]]
    then
        echo
        echo "RUNNING ON OKD.  APPLYING SCC nonroot-builder:"
        oc apply -f ${EL_CICD_RESOURCES_DIR}/nonroot-builder.yaml
        if [[ -z ${JENKINS_IMAGE_PULL_SECRET} ]]
        then
            JENKINS_IMAGE_PULL_SECRET=$(oc get secrets -o custom-columns=:'metadata.name' -n ${ONBOARDING_MASTER_NAMESPACE} | grep deployer-dockercfg)
        fi
    fi

    _create_meta_info_file
    
    _helm_repo_add_and_update_elCicdCharts
    
    echo
    JENKINS_OPENSHIFT_ENABLE_OAUTH=$([[ OKD_VERSION ]] && echo 'true' || echo 'false')
    set -x
    helm upgrade --atomic --install --history-max=1 \
        --set-string profiles='{onboarding}' \
        --set-string elCicdDefs.JENKINS_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME} \
        --set-string elCicdDefs.JENKINS_URL=${JENKINS_URL} \
        --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH=${JENKINS_OPENSHIFT_ENABLE_OAUTH} \
        --set-string elCicdDefs.CPU_LIMIT=${JENKINS_CPU_LIMIT} \
        --set-string elCicdDefs.MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
        --set-string elCicdDefs.VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
        --set-string elCicdDefs.JENKINS_IMAGE_PULL_SECRET=${JENKINS_IMAGE_PULL_SECRET} \
        --set-string elCicdDefs.EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
        --set-file 'elCicdDefs.${CONFIG|EL_CICD_META_INFO}'=${META_INFO_FILE} \
        -n ${ONBOARDING_MASTER_NAMESPACE} \
        -f ${EL_CICD_CONFIG_HELM_DIR}/default-${ONBOARDING_SERVER_TYPE}-onboarding-values.yaml \
        -f ${EL_CICD_HELM_DIR}/jenkins-config-values.yaml \
        -f ${EL_CICD_HELM_DIR}/${ONBOARDING_SERVER_TYPE}-onboarding-values.yaml \
        jenkins \
        elCicdCharts/elCicdChart
    set +x
    
    rm ${META_INFO_FILE}

    echo
    echo 'Jenkins up, sleep for 5 more seconds to make sure server REST api is ready'
    sleep 5
    
    set -x
    helm upgrade --wait --wait-for-jobs --install --history-max=1  \
        --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT} \
        -n ${ONBOARDING_MASTER_NAMESPACE} \
        -f ${EL_CICD_HELM_DIR}/jenkins-pipeline-sync-job-values.yaml \
        jenkins-sync \
        elCicdCharts/elCicdChart
    set +ex
}

_helm_repo_add_and_update_elCicdCharts() {
    echo
    helm repo add elCicdCharts --force-update ${EL_CICD_HELM_REPOSITORY}
    helm repo update elCicdCharts
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
        until !(oc project ${NAMESPACE} > /dev/null 2>&1)
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
        echo "Base agent found: to manually rebuild Jenkins Agents, run the 'oc el-cicd-adm --jenkins-agents <el-CICD config file>'"
    fi
}

__run_custom_config_scripts() {
    local SCRIPTS=$(find "${EL_CICD_CONFIG_BOOTSTRAP_DIR}" -type f -executable \( -name "${ONBOARDING_SERVER_TYPE}-*.sh" -o -name 'all-*.sh' \) | sort | tr '\n' ' ')
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