#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_tear_down_lab_environment() {
    set -eE
    
    echo
    echo "${DEV_TEAR_DOWN_WELCOME_MSG}"

    __gather_lab_tear_down_info

    __summarize_and_confirm_lab_tear_down

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        _remove_existing_crc
    fi

    if [[ ${REMOVE_IMAGE_REGISTRY} == ${_YES} ]]
    then
        _remove_image_registry

        __remove_whitelisted_image_registry_host_names
    fi

    if [[ ${REMOVE_IMAGE_REGISTRY_NFS} == ${_YES} ]]
    then
        __remove_image_registry_nfs_share
    fi

    if [[ ${REMOVE_GIT_REPOS} == ${_YES} && ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        __delete_remote_el_cicd_git_repos
    fi
}

__gather_lab_tear_down_info() {
    echo
    if [[ -d ${HOME}/.crc ]]
    then
        REMOVE_CRC=$(_get_yes_no_answer 'Do you wish to tear down OpenShift Local? [Y/n] ')
    else
        echo 'OpenShift Local installation not found.  Skipping...'
    fi

    if [[ ${REMOVE_CRC} != ${_YES} ]]
    then
        oc whoami > /dev/null 2>&1 
        if [[ ! -z $(oc get project --ignore-not-found ${DEMO_IMAGE_REGISTRY}) ]]
        then
            echo
            REMOVE_IMAGE_REGISTRY=$(_get_yes_no_answer 'Do you wish to remove the development image registry? [Y/n] ')
        fi
    fi

    if [[ -d ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} && (${REMOVE_IMAGE_REGISTRY} == ${_YES} || ${REMOVE_CRC} == ${_YES})  ]]
    then
        REMOVE_IMAGE_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to remove the image registry NFS share? [Y/n] ')
    fi

    if [[ ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        echo
        REMOVE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to remove the el-CICD Git repositories? [Y/n] ')
        if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
        then
            echo
            read -p "Enter GitHub user name (leave blank to leave as ${EL_CICD_ORGANIZATION}): " EL_CICD_ORGANIZATION_TEMP
            EL_CICD_ORGANIZATION=${EL_CICD_ORGANIZATION_TEMP:-${EL_CICD_ORGANIZATION}}

            read -s -p "Enter GitHub Personal Access Token (leave blank to use credential file): " EL_CICD_GIT_REPO_ACCESS_TOKEN
        fi
    fi
}

__summarize_and_confirm_lab_tear_down() {
    echo
    echo "${_BOLD}===================== SUMMARY =====================${_REGULAR}"
    echo 

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        echo "OpenShift Local ${_BOLD}WILL${_REGULAR} be torn down.  The image registry ${_BOLD}WILL${_REGULAR} also be torn down as a result."
    else 
        echo "OpenShift Local will ${_BOLD}NOT${_REGULAR} be torn down."

        if [[ ${REMOVE_IMAGE_REGISTRY} == ${_YES} ]]
        then
            echo "The image registry ${_BOLD}WILL${_REGULAR} be torn down."
        else
            echo "The image registry will ${_BOLD}NOT${_REGULAR} be torn down."
        fi
    fi

    echo -n "The image registry NFS share "
    if [[ ${REMOVE_IMAGE_REGISTRY_NFS} == ${_YES} ]]
    then
        echo -n "${_BOLD}WILL${_REGULAR}"
    else
        echo -n "will ${_BOLD}NOT${_REGULAR}"
    fi
    echo " be deleted and removed."

    echo -n "All el-CICD Git repositories "
    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        echo -n "${_BOLD}WILL${_REGULAR}"
    else
        echo -n "will ${_BOLD}NOT${_REGULAR}"
    fi
    echo " be removed from ${EL_CICD_ORGANIZATION} on the Git host."
    
    echo
    echo "${_BOLD}=================== END SUMMARY ===================${_REGULAR}"

    _confirm_continue
}

_remove_existing_crc() {
    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    if [[ ! -z ${CRC_EXEC} ]]
    then
        echo
        echo 'Stopping the OpenShift Local cluster'
        ${CRC_EXEC} stop 2>/dev/null
        ${CRC_EXEC} delete  2>/dev/null
        echo 'Cleaning up the OpenShift Local install'
        ${CRC_EXEC} cleanup  2>/dev/null
    fi

    echo
    echo 'Removing old OpenShift Local installation directories'
    rm -rfv ${EL_CICD_HOME}/crc*/
    rm -rfv ${HOME}/.crc
}

_remove_image_registry() {
    if [[ ! -z $(helm list -q -n ${DEMO_IMAGE_REGISTRY} -f ${DEMO_IMAGE_REGISTRY}) ]]
    then
        echo
        echo "Uninstalling ${DEMO_IMAGE_REGISTRY}..."
        helm uninstall ${DEMO_IMAGE_REGISTRY} -n ${DEMO_IMAGE_REGISTRY}

        local REGISTRY_NAMES=$(echo ${DEMO_IMAGE_REGISTRY_NAMES} | tr ':' ' ')
        local IMAGE_CONFIG_CLUSTER=$(oc get image.config.openshift.io/cluster -o yaml)
        for REGISTRY_NAME in ${REGISTRY_NAMES}
        do
            local HOST_DOMAIN=${REGISTRY_NAME}-${DEMO_IMAGE_REGISTRY}.${CLUSTER_WILDCARD_DOMAIN}

            IMAGE_CONFIG_CLUSTER=$(echo "${IMAGE_CONFIG_CLUSTER}" | grep -v "\- ${HOST_DOMAIN}")
        done
        echo "${IMAGE_CONFIG_CLUSTER}" | oc apply -f -
    fi
    oc delete project --ignore-not-found ${DEMO_IMAGE_REGISTRY}
}

__remove_image_registry_nfs_share() {
    if [[ -d ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} ]]
    then
        echo
        echo "Removing ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} and delisting it as an NFS share"

        sudo rm -rf ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}
        sudo cat /etc/exports | sudo grep -v ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} > /etc/exports
        sudo exportfs -a
        sudo systemctl restart nfs-server.service
    else
        echo
        echo "${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} not found.  Skipping..."
    fi
}

__remove_whitelisted_image_registry_host_names() {
    local IMAGE_REGISTRYS_LIST=(${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV})
    local IMAGE_REGISTRYS=''
    for REPO in ${IMAGE_REGISTRYS_LIST[@]}
    do
        IMAGE_REGISTRYS="${IMAGE_REGISTRYS} $(eval echo \${${REPO}${IMAGE_REGISTRY_USERNAME_POSTFIX}})"
    done
    IMAGE_REGISTRYS=$(echo ${IMAGE_REGISTRYS} | xargs -n1 | sort -u | xargs)

    echo
    local HOST_NAMES=''
    for REGISTRY_NAME in ${IMAGE_REGISTRYS}
    do
        HOST_DOMAIN=${REGISTRY_NAME}-${DEMO_IMAGE_REGISTRY}.${CLUSTER_WILDCARD_DOMAIN}

        echo "Removing whitelisted ${HOST_DOMAIN} as an insecure image registry."
        oc get image.config.openshift.io/cluster -o yaml | grep -v ${HOST_DOMAIN} | oc apply -f -
    done
}

__delete_remote_el_cicd_git_repos() {
    echo
    local ALL_EL_CICD_DIRS=$(echo "${EL_CICD_REPO_DIRS}:${EL_CICD_DOCS_REPO}:${EL_CICD_TEST_PROJECTS}" | tr ':' ' ')
    for GIT_REPO_DIR in ${ALL_EL_CICD_DIRS}
    do
        __remove_git_repo ${GIT_REPO_DIR}
    done
}

__remove_git_repo() {
    GIT_REPO_DIR=${1}

    local __PAT=${EL_CICD_GIT_REPO_ACCESS_TOKEN:-$(cat ${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE})}

    local REMOTE_GIT_DIR_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -u :${__PAT} \
        ${GITHUB_REST_API_HDR}  \
        https://${EL_CICD_GIT_API_URL}/repos/${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR})
    if [[ ${REMOTE_GIT_DIR_EXISTS} == 200 ]]
    then
        curl -X DELETE -sI -o /dev/null -u :${__PAT} \
            ${GITHUB_REST_API_HDR}  https://${EL_CICD_GIT_API_URL}/repos/${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR}
        echo "${GIT_REPO_DIR} deleted from Git host ${EL_CICD_GIT_DOMAIN}/${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR}"
    else
        echo "${GIT_REPO_DIR} NOT FOUND on Git host, or the Access Token has expired. Skipping..."
    fi
}