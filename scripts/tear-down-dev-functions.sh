#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

__tear_down_dev_environment() {
    echo
    echo "${DEV_TEAR_DOWN_WELCOME_MSG}"

    __gather_dev_tear_down_info

    __summarize_and_confirm_dev_tear_down

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        _remove_existing_crc
    fi

    if [[ ${REMOVE_DOCKER_REGISTRY} == ${_YES} ]]
    then
        __remove_docker_registry

        __remove_whitelisted_docker_registry_host_names
    fi

    if [[ ${REMOVE_DOCKER_REGISTRY_NFS} == ${_YES} ]]
    then
        __remove_docker_registry_nfs_share
    fi

    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        __delete_remote_el_cicd_git_repos
    fi
}

__gather_dev_tear_down_info() {
    echo
    if [[ -d ${HOME}/.crc ]]
    then
        REMOVE_CRC=$(_get_yes_no_answer 'Do you wish to tear down CRC? [Y/n] ')
    else
        echo 'CRC installation not found.  Skipping...'
    fi

    if [[ ${REMOVE_CRC} != ${_YES} && ! -z $(oc get project --ignore-not-found ${DOCKER_REGISTRY_NAMESPACE}) ]]
    then
        echo
        REMOVE_DOCKER_REGISTRY=$(_get_yes_no_answer 'Do you wish to remove the development image registry? [Y/n] ')
    fi

    if [[ ${REMOVE_DOCKER_REGISTRY} == ${_YES} && -d ${DOCKER_REGISTRY_DATA_NFS_DIR} ]]
    then
        REMOVE_DOCKER_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to remove the image registry NFS share? [Y/n] ')
    fi

    echo
    REMOVE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to remove the el-CICD Git repositories? [Y/n] ')
    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        echo
        read -p "Enter GitHub user name: " GITHUB_USER
    fi
}

__summarize_and_confirm_dev_tear_down() {
    echo
    echo "TEAR DOWN SUMMARY:"
    echo 

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        echo "CRC WILL be torn down.  The image registry WILL also be torn down as a result."
    else 
        echo "CRC will NOT be torn down."

        if [[ ${REMOVE_DOCKER_REGISTRY} == ${_YES} ]]
        then
            echo "The image registry WILL be torn down."
        else
            echo "The image registry will NOT be torn down."
        fi
    fi

    echo -n "The image registry NFS share "
    if [[ ${REMOVE_DOCKER_REGISTRY_NFS} == ${_YES} ]]
    then
        echo -n "WILL"
    else
        echo -n "will NOT"
    fi
    echo " be deleted and removed."

    echo -n "All el-CICD Git repositories "
    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        echo -n "WILL"
    else
        echo -n "will NOT"
    fi
    echo " be removed from the Git host."

    _confirm_continue
}

_remove_existing_crc() {
    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    if [[ ! -z ${CRC_EXEC} ]]
    then
        echo
        echo 'Cleaning up old CRC install'
        ${CRC_EXEC} stop
        ${CRC_EXEC} delete
        ${CRC_EXEC} cleanup
    fi

    echo
    echo 'Removing old CRC installation directories'
    rm -rfv ${EL_CICD_HOME}/crc*/
    rm -rfv ${HOME}/.crc
}

__remove_docker_registry() {
    _delete_namespace  ${DOCKER_REGISTRY_NAMESPACE}

    local REGISTRY_NAMES=$(echo ${DOCKER_REGISTRY_USER_NAMES} | tr ':' ' ')
    local IMAGE_CONFIG_CLUSTER=$(oc get image.config.openshift.io/cluster -o yaml)
    for REGISTRY_NAME in ${REGISTRY_NAMES}
    do
        local HOST_DOMAIN=${REGISTRY_NAME}-docker-registry.${CLUSTER_WILDCARD_DOMAIN}

        IMAGE_CONFIG_CLUSTER=$(echo "${IMAGE_CONFIG_CLUSTER}" | grep -v "\- ${HOST_DOMAIN}")
    done
    echo "${IMAGE_CONFIG_CLUSTER}" | oc apply -f -

    local LOCAL_NFS_IP=$(ip -j route get 8.8.8.8 | jq -r '.[].prefsrc')
    sed -e "s/%LOCAL_NFS_IP%/${LOCAL_NFS_IP}/" ${SCRIPTS_RESOURCES_DIR}/docker-registry-pv-template.yml | \
        oc delete --ignore-not-found -f -
}

__remove_docker_registry_nfs_share() {
    if [[ -d ${DOCKER_REGISTRY_DATA_NFS_DIR} ]]
    then
        echo
        echo "Removing ${DOCKER_REGISTRY_DATA_NFS_DIR} and delisting it as an NFS share"

        sudo rm -rf ${DOCKER_REGISTRY_DATA_NFS_DIR}
        sudo sed -i "\|${DOCKER_REGISTRY_DATA_NFS_DIR}|d" /etc/exports
        sudo exportfs -a
        sudo systemctl restart nfs-server.service
    else
        echo
        echo "${DOCKER_REGISTRY_DATA_NFS_DIR} not found.  Skipping..."
    fi
}

__remove_whitelisted_docker_registry_host_names() {
    local IMAGE_REPOS_LIST=(${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV})
    local IMAGE_REPOS=''
    for REPO in ${IMAGE_REPOS_LIST[@]}
    do
        IMAGE_REPOS="${IMAGE_REPOS} $(eval echo \${${REPO}${IMAGE_REPO_USERNAME_POSTFIX}})"
    done
    IMAGE_REPOS=$(echo ${IMAGE_REPOS} | xargs -n1 | sort -u | xargs)

    echo
    local HOST_NAMES=''
    for REGISTRY_NAME in ${IMAGE_REPOS}
    do
        HOST_DOMAIN=${REGISTRY_NAME}-docker-registry.${CLUSTER_WILDCARD_DOMAIN}

        echo "Removing whitelisted ${HOST_DOMAIN} as an insecure image registry."
        oc get image.config.openshift.io/cluster -o yaml | grep -v ${HOST_DOMAIN} | oc apply -f -
    done
}

__delete_remote_el_cicd_git_repos() {
    local ALL_EL_CICD_DIRS=$(echo "${EL_CICD_REPO_DIRS}:${EL_CICD_DOCS}:${EL_CICD_TEST_PROJECTS}" | tr ':' ' ')
    for EL_CICD_DIR in ${ALL_EL_CICD_DIRS}
    do
        __remove_git_repo ${EL_CICD_DIR}
    done
}

__remove_git_repo() {
    GIT_REPO_DIR=${1}

    local REMOTE_GIT_DIR_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -u :$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}) \
        -H "Accept: application/vnd.github.v3+json"  \
        https://${EL_CICD_GIT_API_URL}/repos/${GITHUB_USER}/${GIT_REPO_DIR})
    if [[ ${REMOTE_GIT_DIR_EXISTS} == 200 ]]
    then
        curl -X DELETE -sI -o /dev/null -u :$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}) \
            -H "Accept: application/vnd.github.v3+json"  https://${EL_CICD_GIT_API_URL}/repos/${GITHUB_USER}/${GIT_REPO_DIR}
        echo "${GIT_REPO_DIR} deleted on Git host"
    else
        echo "${GIT_REPO_DIR} NOT FOUND on Git host. Skipping..."
    fi
}