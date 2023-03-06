#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export _TRUE='true'
export _FALSE='false'

export _YES='Yes'
export _NO='No'

_bootstrap_el_cicd() {
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

    _build_jenkins_agents_if_necessary

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

_create_and_source_meta_info_file() {
    set -e -o allexport
    
    echo
    echo "GENERATING CONFIG FILES:"

    source ${ROOT_CONFIG_FILE}

    local EL_CICD_SCRIPTS_CONFIG_DIR=${EL_CICD_SCRIPTS_DIR}/config

    local EL_CICD_SYSTEM_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-system.conf

    local EL_CICD_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-${ONBOARDING_SERVER_TYPE}.conf

    local EL_CICD_BOOTSTRAP_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-default-bootstrap.conf
    local EL_CICD_CONFIG_BOOTSTRAP_CONF=${EL_CICD_CONFIG_BOOTSTRAP_DIR}/default-bootstrap.conf

    local EL_CICD_RUNTIME_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-default-runtime.conf
    local EL_CICD_CONFIG_RUNTIME_CONF=${EL_CICD_CONFIG_BOOTSTRAP_DIR}/default-runtime.conf

    local EL_CICD_LAB_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-${ONBOARDING_SERVER_TYPE}-lab-setup.conf
    local EL_CICD_CONFIG_LAB_CONF=${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${ONBOARDING_SERVER_TYPE}-lab-setup.conf

    EL_CICD_META_INFO_FILE=/tmp/el_cicd_meta_info_file.conf
    EL_CICD_BOOTSTRAP_META_INFO_FILE=/tmp/el_cicd_bootstrap_meta_info_file.conf

    if [[ ! -z ${EL_CICD_USE_LAB_CONFIG} ]]
    then
        LAB_CONFIG_FILE_LIST="${EL_CICD_CONFIG_LAB_CONF} ${EL_CICD_LAB_CONF}"
    fi

    local CONFIG_FILE_LIST="${EL_CICD_SYSTEM_CONF} ${ROOT_CONFIG_FILE}  ${LAB_CONFIG_FILE_LIST} ${EL_CICD_CONF} ${EL_CICD_CONFIG_RUNTIME_CONF} ${EL_CICD_RUNTIME_CONF}"
    __create_meta_info_file "${CONFIG_FILE_LIST}" ${EL_CICD_META_INFO_FILE}

    local BOOTSTRAP_CONFIG_FILE_LIST="${EL_CICD_META_INFO_FILE} ${EL_CICD_CONFIG_BOOTSTRAP_CONF} ${EL_CICD_BOOTSTRAP_CONF}"
    __create_meta_info_file "${BOOTSTRAP_CONFIG_FILE_LIST}" ${EL_CICD_BOOTSTRAP_META_INFO_FILE}

    source ${EL_CICD_BOOTSTRAP_META_INFO_FILE}

    echo
    echo 'el-CICD environment loaded'
    
    set +e +o allexport
}

__create_meta_info_file() {
    local CONF_FILE_LIST=${1}
    local META_INFO_FILE=${2}

    local META_INFO_FILE_TMP=${META_INFO_FILE}.tmp

    rm -f ${META_INFO_FILE} ${META_INFO_FILE_TMP}

    # ignore duplicate values: config file precedence is left to right
    awk -F= '!line[$1]++' ${CONF_FILE_LIST} >> ${META_INFO_FILE_TMP}

    # remove blank lines, comments, and any trailing whitespace
    sed -i -e 's/\s*$//' -e '/^$/d' -e '/^#.*$/d' ${META_INFO_FILE_TMP}

    sort -o ${META_INFO_FILE_TMP} ${META_INFO_FILE_TMP}

    source ${META_INFO_FILE_TMP}
    cat ${META_INFO_FILE_TMP} | envsubst > ${META_INFO_FILE}

    rm -f ${META_INFO_FILE_TMP}

    echo
    echo "${_BOLD}${META_INFO_FILE}${_REGULAR} created from the following config files:"
    for CONF_FILE in ${CONF_FILE_LIST}
    do
        echo "- $(basename ${CONF_FILE}) "
    done
    sleep 2
}

__gather_and_confirm_bootstrap_info_with_user() {
    _check_sealed_secrets

    IMAGE_URL=docker://${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME}
    local HAS_JENKINS_IMAGE=$(skopeo inspect --format '{{.Name}}({{.Digest}})' --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} ${IMAGE_URL} 2> /dev/null)
    if [[ ! -z ${HAS_JENKINS_IMAGE} ]]
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

    _create_env_image_registry_secrets

    __create_onboarding_automation_server
}

__summarize_and_confirm_bootstrap_run_with_user() {
    echo
    echo "${_BOLD}===================== SUMMARY =====================${_REGULAR}"
    echo
    
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo "${_BOLD}Please read CAREFULLY and verify this information is correct before proceeding.${_REGULAR}"
    echo
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
         echo "Install Sealed Secrets version ${SEALED_SECRET_RELEASE_VERSION}? ${INSTALL_KUBESEAL}"
    else
        echo "SEALED SECRETS WILL NOT BE INSTALLED.  A Sealed Secrets version in el-CICD configuration is not defined."
    fi

    echo
    echo "Cluster API hostname? '${CLUSTER_API_HOSTNAME}'"
    echo "Cluster wildcard Domain? '*.${CLUSTER_WILDCARD_DOMAIN}'"

    if [[ ! -z $(oc get project ${ONBOARDING_MASTER_NAMESPACE} --ignore-not-found -o name) ]]
    then
        echo
        echo "${_BOLD}WARNING:${_REGULAR} '${ONBOARDING_MASTER_NAMESPACE}' was found".
        echo
        echo "${_BOLD}The onboarding server environment will be reinstalled.${_REGULAR}"
    else
        echo
        echo "${_BOLD}WARNING:${_REGULAR} '${ONBOARDING_MASTER_NAMESPACE}' was NOT found".
        echo
        echo "${_BOLD}The onboarding server environment will be created and installed.${_REGULAR}"
    fi

    if [[ ! -z ${UPDATE_EL_CICD_JENKINS} ]]
    then
        echo
        echo "Update/build el-CICD Jenkins image? ${UPDATE_EL_CICD_JENKINS}"
    else
        echo
        echo "${_BOLD}WARNING:${_REGULAR} '${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME}' image was not found."
        echo
        echo "${_BOLD}el-CICD Jenkins WILL BE BUILT.${_REGULAR}"
    fi

    if [[ $(_is_true ${JENKINS_SKIP_AGENT_BUILDS}) != ${_TRUE} && $(_base_jenkins_agent_exists) == ${_FALSE} ]]
    then
        echo
        echo "${_BOLD}WARNING:${_REGULAR} '${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT}' image was not found,"
        echo "          and JENKINS_SKIP_AGENT_BUILDS is not ${_TRUE}."
        echo
        echo "${_BOLD}ALL JENKINS AGENTS WILL BE BUILT.${_REGULAR}"
        MUST_BUILD_JENKINS_AGENTS=${_TRUE}
    else
        if [[ $(_is_true ${JENKINS_SKIP_AGENT_BUILDS}) == ${_TRUE} ]]
        then
            echo
            echo "JENKINS_SKIP_AGENT_BUILDS is true."
        else
            echo
            echo "'${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT}' was found."
        fi
        echo "To manually rebuild Jenkins Agents, run the 'oc el-cicd-adm --agents <el-CICD config file>'"
    fi
    echo
    echo "${_BOLD}=================== END SUMMARY ===================${_REGULAR}"

    _confirm_continue
}

__create_onboarding_automation_server() {
    echo
    set -e
    if [[ ${OKD_VERSION} ]]
    then
        echo
        echo "RUNNING ON OKD.  APPLYING SCC nonroot-builder:"
        oc apply -f ${EL_CICD_RESOURCES_DIR}/nonroot-builder.yaml
    fi

    echo
    JENKINS_OPENSHIFT_ENABLE_OAUTH=$([[ OKD_VERSION ]] && echo 'true' || echo 'false')
    set -x
    helm upgrade --atomic --install --history-max=1 \
        --set-string profiles="{onboarding${JENKINS_PERSISTENT:+,jenkinsPersistent}}" \
        --set-string elCicdDefs.JENKINS_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME} \
        --set-string elCicdDefs.JENKINS_URL=${JENKINS_URL} \
        --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH=${JENKINS_OPENSHIFT_ENABLE_OAUTH} \
        --set-string elCicdDefs.CPU_REQUEST=${JENKINS_CPU_REQUEST} \
        --set-string elCicdDefs.CPU_LIMIT=${JENKINS_CPU_LIMIT} \
        --set-string elCicdDefs.MEMORY_REQUEST=${JENKINS_MEMORY_REQUEST} \
        --set-string elCicdDefs.MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
        --set-string elCicdDefs.VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
        --set-string elCicdDefs.EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
        --set-file 'elCicdDefs.${CONFIG|EL_CICD_META_INFO}'=${EL_CICD_META_INFO_FILE} \
        --set-file elCicdDefs.CASC_FILE=${EL_CICD_CONFIG_JENKINS_DIR}/${ONBOARDING_SERVER_TYPE}-jenkins-casc.yaml \
        --set-file elCicdDefs.PLUGINS_FILE=${EL_CICD_CONFIG_JENKINS_DIR}/${ONBOARDING_SERVER_TYPE}-plugins.txt \
        -n ${ONBOARDING_MASTER_NAMESPACE} \
        -f ${EL_CICD_CONFIG_DIR}/${EL_CICD_CHART_VALUES_DIR}/default-${ONBOARDING_SERVER_TYPE}-onboarding-values.yaml \
        -f ${EL_CICD_DIR}/${EL_CICD_CHART_VALUES_DIR}/jenkins-config-values.yaml \
        -f ${EL_CICD_DIR}/${EL_CICD_CHART_VALUES_DIR}/${ONBOARDING_SERVER_TYPE}-onboarding-pipeline-values.yaml \
        jenkins \
        elCicdCharts/elCicdChart
    set +x

    echo
    echo 'JENKINS UP: sleep for 5 seconds to make sure server REST api is ready'
    sleep 5

    if [[ ! -z $(helm list -n ${ONBOARDING_MASTER_NAMESPACE} | grep jenkins-pipeline-sync) ]]
    then
        echo
        helm uninstall jenkins-pipeline-sync -n ${ONBOARDING_MASTER_NAMESPACE}
    fi

    echo
    set -x
    helm upgrade --wait --wait-for-jobs --install --history-max=1  \
                --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT} \
                -n ${ONBOARDING_MASTER_NAMESPACE} \
                -f ${EL_CICD_DIR}/${EL_CICD_CHART_VALUES_DIR}/jenkins-pipeline-sync-job-values.yaml \
                jenkins-pipeline-sync \
                elCicdCharts/elCicdChart
    set +x
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