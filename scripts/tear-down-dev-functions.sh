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
    elif [[ ${REMOVE_DOCKER_REGISTRY} == ${_YES} ]]
    then
        __remove_docker_registry
        
        if [[ ${REMOVE_DOCKER_REGISTRY_NFS} == ${_YES} ]]
        then
            __remove_docker_registry_nfs_share
        fi

        __remove_whitelisted_docker_registry_host_names
    fi

    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        __remove_git_repos
    else
        __remove_git_deploy_keys
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

    if [[ ${REMOVE_CRC} != ${_YES} && ! -d $(oc get project --ignore-not-found ${DOCKER_REGISTRY_NAMESPACE}) ]]
    then
        echo
        REMOVE_DOCKER_REGISTRY=$(_get_yes_no_answer 'Do you wish to remove the development Docker Registry? [Y/n] ')
    fi

    if [[ -d ${DOCKER_REGISTRY_DATA_NFS_DIR} ]]
    then
        REMOVE_DOCKER_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to remove the Docker Registry NFS share? [Y/n] ')
    fi

    echo
    REMOVE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to remove the el-CICD Git repositories? [Y/n] ')
}

__summarize_and_confirm_dev_tear_down() {
    echo
    echo "TEAR DOWN SUMMARY:"
    echo 

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        echo "CRC WILL be torn down.  The Docker Registry will also be torn down as a result."
    else 
        echo "CRC will NOT be torn down."

        if [[ ${REMOVE_DOCKER_REGISTRY} == ${_YES} ]]
        then
            echo "The Docker Registry will be torn down."
        fi
    fi

    echo -n "The Docker Registry NFS share "
    if [[ ${REMOVE_DOCKER_REGISTRY_NFS} == ${_YES} ]]
    then
        echo -n "WILL"
    else
        echo -n "will NOT"
    fi
    echo " be deleted and removed."

    echo -n "All el-CICD Git repositories will "
    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        echo "be removed."
    else
        echo "be preserved, but deployment keys will be removed."
    fi

    _confirm_continue
}

_remove_existing_crc() {
    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    if [[ ! -z ${CRC_EXEC} ]]
    then
        echo
        echo 'Cleaning up old CRC install'
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

    if [[ ${REMOVE_INSECURE_REGISTRIES} == ${_YES} ]]
    then
        echo "Removing array for whitelisting insecure registries."
        oc patch image.config.openshift.io/cluster --type json   -p='[{"op": "remove", "path": "/spec/registrySources/insecureRegistries"}]'
        echo "ALL WHITELISTED INSECURE IMAGE REGISTRIES DELISTED FROM CLUSTER"
    fi

    oc delete --ignore-not-found -f ${SCRIPTS_RESOURCES_DIR}/docker-registry-pv.yml
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

__remove_git_repos() {
    echo 'TODO: implement __remove_git_repos'
}

__remove_git_deploy_keys() {
    echo 'TODO: implement __remove_git_deploy_keys'
}