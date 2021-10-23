#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

__bootstrap_dev_environment() {
    echo
    echo "${DEV_SETUP_WELCOME_MSG}"

    __gather_dev_setup_info

    __summarize_and_confirm_dev_setup_info

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        __bootstrap_clean_crc
    fi

    __additional_cluster_config

    if [[ ${INSTALL_DOCKER_REGISTRY} == ${_YES} ]]
    then
        __setup_docker_registry
    fi

    if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
    then
        __create_credentials
    fi
}

__gather_dev_setup_info() {
    echo
    CRC_TAR_XZ=$(ls ${EL_CICD_HOME}/crc-*.tar.xz 2>/dev/null | wc -l)
    if [[ ${CRC_TAR_XZ} == '1' && -f "${EL_CICD_HOME}/pull-secret.txt" ]]
    then
        echo "CRC needs a minimum of 8 vCPUs, 48GB of RAM, and 128GB of disk space."
        echo
        echo "WARNING: el-CICD will completely remove any old CRC installs."
        SETUP_CRC=$(_get_yes_no_answer 'Do you wish to setup CRC? [Y/n] ')
    else
        echo 'WARNING: CRC tar.xz and/or pull-secret.txt not found in el-CICD home directory.  Skipping...'
    fi

    echo
    INSTALL_DOCKER_REGISTRY=$(_get_yes_no_answer 'Do you wish to install the development Docker Registry on your cluster? [Y/n] ')
    if [[ ${INSTALL_DOCKER_REGISTRY} == ${_YES} ]]
    then
        SETUP_DOCKER_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to setup an NFS share for your Docker Registry (for developers)? [Y/n] ')
    fi

    echo
    GENERATE_CRED_FILES=$(_get_yes_no_answer 'Do you to (re)generate the credential files? [Y/n] ')
    if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
    then
        echo
        echo
        read -p "Enter GitHub user/organization: " GITHUB_USER
        echo -n "Enter GitHub personal access token:"

        stty -echo
        read GITHUB_ACCESS_TOKEN
        stty echo
        read -p "Enter GitHub REST URL (leave blank if using public GitHub site): " GITHUB_URL
    fi
}

__summarize_and_confirm_dev_setup_info() {
    echo
    echo "SUMMARY:"
    echo

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        echo "CRC will be setup.  Login to kubeadmin will be automated."
    else
        echo "CRC will NOT be setup.  You should already be logged into a cluster as a cluster admin."
    fi

    if [[ ${INSTALL_DOCKER_REGISTRY} == ${_YES} ]]
    then
        echo -n "Docker Registry will be installed on your cluster WITH"
        if [[ ${SETUP_DOCKER_REGISTRY_NFS} != ${_YES} ]]
        then
            echo -n "OUT"
        fi
        echo " an NFS share."
    fi
    echo "Two el-CICD functional and six demo project Git repositories will be pushed to the ${GITHUB_USER} GitHub account."

    if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
    then
        echo 'Credential files WILL be (re)generated.'
    else
        echo 'Credential files will NOT be (re)generated.'
    fi

    _confirm_continue
}

__bootstrap_clean_crc() {
    _remove_existing_crc

    echo
    echo "Extracting CRC tar.xz to ${EL_CICD_HOME}"
    tar -xf ${EL_CICD_HOME}/crc*.tar.xz -C ${EL_CICD_HOME}

    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)


    echo
    ${CRC_EXEC} setup

    echo
    echo "Starting CRC with ${CRC_V_CPU} vCPUs, ${CRC_MEMORY}M memory, and ${CRC_DISK}G disk"
    ${CRC_EXEC} start -c ${CRC_V_CPU} -m ${CRC_MEMORY} -d ${CRC_DISK} -p ${EL_CICD_HOME}/pull-secret.txt

    eval $(${CRC_EXEC} oc-env)
    source <(oc completion ${CRC_SHELL})

    echo
    echo "crc login as kubeadmin"
    local CRC_LOGIN=$(${CRC_EXEC} console --credentials | sed -n 2p | sed -e "s/.*'\(.*\)'/\1/")
    eval ${CRC_LOGIN} --insecure-skip-tls-verify
}

__additional_cluster_config() {
    echo
    for GROUP in ${CRC_TEST_RBAC_GROUPS}
    do
        if [[ ! -z $(oc get groups ${GROUP} 2&> /dev/null) ]]
        then
            echo "Creating test RBAC group '${GROUP}'"
            oc adm groups new ${GROUP}
        else
            echo "RBAC group '${GROUP}' found. Skipping..."
        fi
    done

    echo
    if [[ -z $(oc get secrets -n kube-system | grep sealed-secrets-key) ]]
    then
        echo "Creating Sealed Secrets master key"
        oc create -f ${SCRIPTS_RESOURCES_DIR}//master.key
        oc delete pod --ignore-not-found -n kube-system -l name=sealed-secrets-controller
    else
        echo "Sealed Secrets master key found. Apply manually if still required.  Skipping..."
    fi
}

__create_docker_registry_nfs_share() {
    echo
    if [[ ! -d ${DOCKER_REGISTRY_DATA_NFS_DIR} || -z $(sudo exportfs | grep ${DOCKER_REGISTRY_DATA_NFS_DIR}) ]]
    then
        echo "Creating NFS share on host for Nexus, if necessary: ${DOCKER_REGISTRY_DATA_NFS_DIR}"
        if [[ -z $(cat /etc/exports | grep ${DOCKER_REGISTRY_DATA_NFS_DIR}) ]]
        then
            echo "${DOCKER_REGISTRY_DATA_NFS_DIR} *(rw,sync,all_squash,insecure)" | sudo tee -a /etc/exports
        fi

        sudo mkdir -p ${DOCKER_REGISTRY_DATA_NFS_DIR}
        sudo chown -R nobody:nobody ${DOCKER_REGISTRY_DATA_NFS_DIR}
        sudo chmod 777 ${DOCKER_REGISTRY_DATA_NFS_DIR}
        sudo exportfs -a
        sudo systemctl restart nfs-server.service
    else
        echo 'Nexus NFS Share found.  Skipping...'
    fi
}

__setup_docker_registry() {
    mkdir -p ${TMP_DIR}

    oc new-project ${DOCKER_REGISTRY_NAMESPACE}

    if [[ ${SETUP_DOCKER_REGISTRY_NFS} == ${_YES} ]]
    then
        __create_docker_registry_nfs_share

        oc create -f ${SCRIPTS_RESOURCES_DIR}/docker-registry-pv.yml
    fi

    __generate_deployments

    echo
    oc create -f ${TMP_DIR} -n ${DOCKER_REGISTRY_NAMESPACE}
    # rm -rf ${TMP_DIR}

    echo
    local DCS="$(oc get deploy -o 'custom-columns=:.metadata.name' -n ${DOCKER_REGISTRY_NAMESPACE} | xargs)"
    for DC in ${DCS}
    do
        echo
        oc rollout status deploy/${DC} -n ${DOCKER_REGISTRY_NAMESPACE}
    done

    echo
    echo 'Docker Registry is up!'
    sleep 2
}

__generate_deployments() {
    local IMAGE_REPOS_LIST=(${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV})
    local IMAGE_REPOS=''
    for REPO in ${IMAGE_REPOS_LIST[@]}
    do
        IMAGE_REPOS="${IMAGE_REPOS} $(eval echo \${${REPO}${IMAGE_REPO_USERNAME_POSTFIX}})"
    done
    IMAGE_REPOS=$(echo ${IMAGE_REPOS} | xargs -n1 | sort -u | xargs)

    for REGISTRY_NAME in ${IMAGE_REPOS}
    do
        local TMP_FILE=${TMP_DIR}/docker-registry-${REGISTRY_NAME}-tmp.yml
        local OUTPUT_FILE=${TMP_DIR}/docker-registry-${REGISTRY_NAME}.yml

        sed -e "s/%REGISTRY_NAME%/${REGISTRY_NAME}/g"  \
            -e "s/%CLUSTER_WILDCARD_DOMAIN%/${CLUSTER_WILDCARD_DOMAIN}/g" \
            ${SCRIPTS_RESOURCES_DIR}/docker-registry-template.yml > ${TMP_FILE}

        if [[ ${SETUP_DOCKER_REGISTRY_NFS} == ${_YES} ]]
        then
            local PATCH=$(sed -e "s/%REGISTRY_NAME%/${REGISTRY_NAME}/g" ${SCRIPTS_RESOURCES_DIR}/nfs-deployment.patch)
            oc patch --local -f "${TMP_FILE}" -p "${PATCH}" -o yaml > ${OUTPUT_FILE}
            awk '/^apiVersion:.*/ { print "---" } 1' ${OUTPUT_FILE} > ${TMP_FILE}
        fi

        local HTPASSWD=$(htpasswd -Bbn ${REGISTRY_NAME} ${DOCKER_REGISTRY_USER_PWD})
        echo '---' >> ${TMP_FILE}
        oc create secret generic ${REGISTRY_NAME}-auth-secret --dry-run=client --from-literal=htpasswd=${HTPASSWD} \
            -n ${DOCKER_REGISTRY_NAMESPACE} -o yaml >> ${TMP_FILE}

        mv ${TMP_FILE} ${OUTPUT_FILE}
    done

    echo
    if [[ -z $(oc describe image.config.openshift.io/cluster | grep 'Insecure Registries') ]]
    then
        echo "Adding array for whitelisting insecure registries."
        oc patch image.config.openshift.io/cluster --type=json \
            -p='[{"op": "add", "path": "/spec/registrySources/insecureRegistries", "value": [] }]'
    else
        echo "Array for whitelisting insecure image registries already exists.  Skipping..."
    fi

    local HOST_NAMES=''
    for REGISTRY_NAME in ${IMAGE_REPOS}
    do
        HOST_DOMAIN=${REGISTRY_NAME}-docker-registry.${CLUSTER_WILDCARD_DOMAIN}

        sed -e "s/%HOST_DOMAIN%/${HOST_DOMAIN}/g" ${SCRIPTS_RESOURCES_DIR}/cluster.patch > ${TMP_DIR}/cluster.patch.tmp
        if [[ -z $(oc get image.config.openshift.io/cluster -o yaml | grep ${HOST_DOMAIN}) ]]
        then
            echo "Whitelisting ${HOST_DOMAIN} as an insecure image registry."
            oc patch image.config.openshift.io/cluster --type=json \
                -p='[{"op": "add", "path": "/spec/registrySources/insecureRegistries/-", "value": "'"${HOST_DOMAIN}"'" }]'
        else
            echo "${HOST_DOMAIN} already whitelisted as insecure registry.  Skipping..."
        fi
    done
}

__create_credentials() {
    mkdir -p ${SECRET_FILE_DIR}

    echo
    echo 'Creating el-CICD read-only Git repository ssh key files'
    local __FILE="${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    local __COMMENT="EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE=${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    ssh-keygen -b 2048 -t rsa -f "${__FILE}" -q -N '' -C "${COMMENT}" 2>/dev/null <<< y >/dev/null

    echo
    echo 'Creating el-CICD-config read-only Git repository ssh key files'
    local __FILE="${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    local __COMMENT="EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE=${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    ssh-keygen -b 2048 -t rsa -f "${__FILE}" -q -N '' -C "${COMMENT}" 2>/dev/null <<< y >/dev/null

    echo
    echo "Creating ${GITHUB_USER} access token file"
    echo ${GITHUB_ACCESS_TOKEN} > ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}

    echo
    CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        echo "Creating the image repository access token file for ${ENV} environment"
        echo ${DOCKER_REGISTRY_ADMIN} > $(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})
    done
}

__set_config(){
    sudo sed -i "s/^\($1\s*=\s*\).*\$/\1$2/" $3
}