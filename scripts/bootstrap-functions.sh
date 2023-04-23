#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export _TRUE='true'
export _FALSE='false'

export _YES='Yes'
export _NO='No'

_bootstrap_el_cicd() {
    if [[ -z "${EL_CICD_MASTER_NAMESPACE}" ]]
    then
        echo "The el-CICD master project must be defined in ${ROOT_CONFIG_FILE}"
        echo "Set the value of EL_CICD_MASTER_NAMESPACE ${ROOT_CONFIG_FILE} and rerun."
        echo "Exiting."
        exit 1
    fi

    __gather_bootstrap_info

    __summarize_and_confirm_bootstrap_run_with_user

    if [[ ${INSTALL_SEALED_SECRETS} == ${_YES} ]]
    then
        _install_sealed_secrets
    fi

    if [[ -z ${JENKINS_MASTER_IMAGE_SHA} ]]
    then
        _build_el_cicd_jenkins_image
    fi

    if [[ ${BUILD_JENKINS_AGENTS} == ${_TRUE} ]]
    then
        _build_el_cicd_jenkins_agent_images
    fi

    __bootstrap_el_cicd_onboarding_server

    echo
    echo 'ADDING EL-CICD CREDENTIALS TO GIT PROVIDER, IMAGE REPOSITORIES, AND JENKINS_MASTER'
    _refresh_credentials

    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then
        _run_custom_config_script bootstrap-non-prod.sh
    fi

    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        _run_custom_config_script bootstrap-prod.sh
    fi
    
    echo 
    echo 'Custom Onboarding Server Boostrap Script(s) Complete'

    echo
    echo "el-CICD Onboarding Server Bootstrap Script Complete:"
    echo "https://${JENKINS_MASTER_URL}"
}

__gather_bootstrap_info() {
    _collect_sealed_secret_info

    EL_CICD_MASTER_NAMESPACE_EXISTS=$(oc get project ${EL_CICD_MASTER_NAMESPACE} -o name --no-headers --ignore-not-found)
    if [[ -z ${EL_CICD_MASTER_NAMESPACE_EXISTS} && -z ${HAS_SEALED_SECRETS} ]]
    then
        _confirm_upgrade_install_sealed_secrets
    fi

    IMAGE_URL=docker://${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME}
    JENKINS_MASTER_IMAGE_SHA=$(skopeo inspect --format '{{.Digest}}' --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} ${IMAGE_URL} 2> /dev/null)
}

__summarize_and_confirm_bootstrap_run_with_user() {
    echo
    echo "===================== ${_BOLD}SUMMARY${_REGULAR} ====================="

    echo
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo "${_BOLD}Please read CAREFULLY and verify this information is correct before proceeding.${_REGULAR}"

    echo
    echo "${_BOLD}${ELCICD_ADM_MSG}${_REGULAR}"

    if [[ ${INSTALL_SEALED_SECRETS} == ${_YES} ]]
    then
        echo
        echo "Sealed Secrets ${SEALED_SECRETS_RELEASE_INFO} ${_BOLD}WILL${_REGULAR} be installed."
    fi

    if [[ -z ${JENKINS_MASTER_IMAGE_SHA} ]]
    then
        echo "${_BOLD}WARNING:${_REGULAR} '${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME}' image was not found."
        echo
        echo "The el-CICD Jenkins image ${_BOLD}WILL BE BUILT${_REGULAR}."
    fi

    local JENKINS_BASE_AGENT_EXISTS=$(_base_jenkins_agent_exists)
    if [[ ${JENKINS_BASE_AGENT_EXISTS} == ${_FALSE} ]]
    then
        echo
        echo "${BOLD}WARNING: THE JENKINS BASE AGENT WAS NOT FOUND.${REGULAR}"
        if [[ $(_get_bool ${JENKINS_SKIP_AGENT_BUILDS}) == ${_TRUE} ]]
        then
            echo
            echo "${BOLD}JENKINS_SKIP_AGENT_BUILDS IS TRUE:${REGULAR} Jenkins agents will not be built."
            echo "To manually rebuild Jenkins Agents, run the 'el-cicd-adm' utility with the --agents flag."
        else
            echo
            echo "All Jenkins agents will be built."
            BUILD_JENKINS_AGENTS=${_TRUE}
        fi
    fi

    echo
    local EL_CICD_MASTER_NAMESPACE_RESULT
    case "${EL_CICD_MASTER_NAMESPACE_EXISTS}" in
        '') EL_CICD_MASTER_NAMESPACE_RESULT='create and install' ;;
         *) EL_CICD_MASTER_NAMESPACE_RESULT='refresh and upgrade' ;;
    esac

    echo "${EL_CICD_MASTER_NAMESPACE} namespace and el-CICD Master: ${_BOLD}${EL_CICD_MASTER_NAMESPACE_RESULT}${_REGULAR}"

    _cluster_info

    echo
    echo "=================== ${_BOLD}END SUMMARY${_REGULAR} ==================="

    _confirm_continue
}

_cluster_info() {
    echo
    oc cluster-info | head -n 1
    echo "Cluster wildcard Domain: ${_BOLD}*.${CLUSTER_WILDCARD_DOMAIN}${_REGULAR}"
    
    echo
    echo "You ${_BOLD}MUST${_REGULAR} be currently logged into a cluster as a cluster admin."
    if [[ ! -z ${OKD_VERSION} ]]
    then
        echo "Logged in as: ${_BOLD}$(oc whoami)${_REGULAR}"
    fi
}

_create_and_source_meta_info_file() {
    set -e -o allexport

    echo
    echo "GENERATING CONFIG FILES:"

    source ${ROOT_CONFIG_FILE}

    local EL_CICD_SCRIPTS_CONFIG_DIR=${EL_CICD_SCRIPTS_DIR}/config

    local EL_CICD_SYSTEM_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-system.conf

    local EL_CICD_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd.conf

    local EL_CICD_BOOTSTRAP_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-default-bootstrap.conf
    local EL_CICD_CONFIG_BOOTSTRAP_CONF=${EL_CICD_CONFIG_BOOTSTRAP_DIR}/config-default-bootstrap.conf

    local EL_CICD_RUNTIME_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-default-runtime.conf
    local EL_CICD_CONFIG_RUNTIME_CONF=${EL_CICD_CONFIG_BOOTSTRAP_DIR}/config-default-runtime.conf

    local EL_CICD_LAB_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/el-cicd-lab-setup.conf
    local EL_CICD_CONFIG_LAB_CONF=${EL_CICD_CONFIG_BOOTSTRAP_DIR}/config-lab-setup.conf

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

_create_rbac_helpers() {
    local HAS_SEALED_SECRETS=$(helm list --short --filter 'sealed-secrets' -n kube-system)
    if [[ ${INSTALL_SEALED_SECRETS} != ${_YES} || ! -z ${HAS_SEALED_SECRETS} ]]
    then
        local SET_PROFILES='--set-string elCicdProfiles={sealed-secrets}'
    fi

    local OKD_RBAC_VALUES_FILE=${OKD_VERSION:+"-f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/el-cicd-okd-scc-nonroot-builder-values.yaml"}

    echo
    echo 'Installing el-CICD RBAC helpers.'
    echo
    set -ex
    helm upgrade --atomic --install --history-max=1 \
        ${SET_PROFILES} ${OKD_RBAC_VALUES_FILE} -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/el-cicd-cluster-rbac-values.yaml \
        -n kube-system \
        el-cicd-cluster-rbac-resources \
        elCicdCharts/elCicdChart
    set +ex
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

    if [[ -z $(oc get project ${EL_CICD_MASTER_NAMESPACE} -o name --no-headers --ignore-not-found)  ]]
    then
        oc new-project ${EL_CICD_MASTER_NAMESPACE}
    fi
    sleep 2

    _create_rbac_helpers

    _create_jenkins_secrets

    __create_onboarding_automation_server
}

__create_onboarding_automation_server() {
    local PROFILES='onboarding'
    PROFILES="${PROFILES}${JENKINS_MASTER_PERSISTENT:+,jenkinsPersistent}"
    PROFILES="${PROFILES}${EL_CICD_MASTER_NONPROD:+,nonprod}"
    PROFILES="${PROFILES}${EL_CICD_MASTER_PROD:+,prod}"

    echo
    echo 'Installing el-CICD Master server'
    echo
    JENKINS_OPENSHIFT_ENABLE_OAUTH=$([[ OKD_VERSION ]] && echo 'true' || echo 'false')
    set -ex
    helm upgrade --atomic --install --history-max=1 \
        --set-string elCicdProfiles="{${PROFILES}}" \
        --set-string elCicdDefs.JENKINS_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME}@${JENKINS_MASTER_IMAGE_SHA} \
        --set-string elCicdDefs.JENKINS_URL=${JENKINS_MASTER_URL} \
        --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH=${JENKINS_OPENSHIFT_ENABLE_OAUTH} \
        --set-string elCicdDefs.JENKINS_CPU_REQUEST=${JENKINS_MASTER_CPU_REQUEST} \
        --set-string elCicdDefs.JENKINS_MEMORY_LIMIT=${JENKINS_MASTER_MEMORY_LIMIT} \
        --set-string elCicdDefs.JENKINS_AGENT_CPU_REQUEST=${JENKINS_AGENT_CPU_REQUEST} \
        --set-string elCicdDefs.JENKINS_AGENT_MEMORY_REQUEST=${JENKINS_AGENT_MEMORY_REQUEST} \
        --set-string elCicdDefs.JENKINS_AGENT_MEMORY_LIMIT=${JENKINS_AGENT_MEMORY_LIMIT} \
        --set-string elCicdDefs.VOLUME_CAPACITY=${JENKINS_MASTER_VOLUME_CAPACITY} \
        --set-string elCicdDefs.EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
        --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${JENKINS_CONFIG_FILE_PATH} \
        --set-file 'elCicdDefs.${CONFIG|EL_CICD_META_INFO}'=${EL_CICD_META_INFO_FILE} \
        --set-file elCicdDefs.JENKINS_CASC_FILE=${EL_CICD_CONFIG_JENKINS_DIR}/${JENKINS_MASTER_CASC_FILE} \
        --set-file elCicdDefs.JENKINS_PLUGINS_FILE=${EL_CICD_CONFIG_JENKINS_DIR}/${JENKINS_MASTER_PLUGINS_FILE} \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        -f ${EL_CICD_CONFIG_DIR}/${CHART_DEPLOY_DIR}/default-el-cicd-master-values.yaml \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/el-cicd-master-pipelines-values.yaml \
        -f ${EL_CICD_DIR}/${JENKINS_CHART_DEPLOY_DIR}/jenkins-config-values.yaml \
        jenkins \
        elCicdCharts/elCicdChart
    set +ex

    echo
    echo 'JENKINS UP: sleep for 5 seconds to make sure server REST api is ready'
    sleep 5

    if [[ ! -z $(helm list -n ${EL_CICD_MASTER_NAMESPACE} | grep jenkins-pipeline-sync) ]]
    then
        echo
        echo 'Removing old Jenkins pipeline sync job.'
        helm uninstall jenkins-pipeline-sync -n ${EL_CICD_MASTER_NAMESPACE}
    fi

    echo
    echo 'Running Jenkins pipeline sync job for el-CICD Master.'
    echo
    set -ex
    helm install --atomic --wait-for-jobs  \
        --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT} \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        -f ${EL_CICD_DIR}/${JENKINS_CHART_DEPLOY_DIR}/jenkins-pipeline-sync-job-values.yaml \
        jenkins-pipeline-sync \
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

_run_custom_config_script() {
    CUSTOM_CONFIG_SCRIPT=${1}

    echo
    echo "LOOKING FOR CUSTOM CONFIGURATION SCRIPT '${CUSTOM_CONFIG_SCRIPT}' in ${EL_CICD_CONFIG_BOOTSTRAP_DIR}..."
    if [[ -f ${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${CUSTOM_CONFIG_SCRIPT} ]]
    then
        echo "${_BOLD}FOUND ${CUSTOM_CONFIG_SCRIPT}${_REGULAR}; running..."
        ${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${CUSTOM_CONFIG_SCRIPT}
        echo "Custom script ${CUSTOM_CONFIG_SCRIPT} completed"
    else
        echo "Custom script '${CUSTOM_CONFIG_SCRIPT}' ${_BOLD}NOT FOUND${_REGULAR}."
    fi
}