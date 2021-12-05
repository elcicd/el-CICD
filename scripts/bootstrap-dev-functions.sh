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
        __remove_docker_registry

        __setup_docker_registry
    fi

    if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
    then
        __create_credentials
    fi

    if [[ ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        __init_el_cicd_repos
    fi
}

__gather_dev_setup_info() {
    echo
    CRC_TAR_XZ=$(ls ${EL_CICD_HOME}/crc-*.tar.xz 2>/dev/null | wc -l)
    if [[ ${CRC_TAR_XZ} == '1' && -f "${EL_CICD_HOME}/pull-secret.txt" ]]
    then
        SETUP_CRC=$(_get_yes_no_answer 'Do you wish to setup CRC? [Y/n] ')
    else
        echo 'WARNING: CRC tar.xz and/or pull-secret.txt not found in el-CICD home directory.  Skipping...'
    fi

    echo
    INSTALL_DOCKER_REGISTRY=$(_get_yes_no_answer 'Do you wish to install the development image registry on your cluster? [Y/n] ')
    if [[ ${INSTALL_DOCKER_REGISTRY} == ${_YES} ]]
    then
        SETUP_DOCKER_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to setup an NFS share for your image registry (only needed for developers)? [Y/n] ')

        if [[ ${SETUP_DOCKER_REGISTRY_NFS} != ${_YES} ]]
        then
            read -s -p "Sudo credentials required: " SUDO_PWD
        fi
    fi

    echo
    GENERATE_CRED_FILES=$(_get_yes_no_answer 'Do you to (re)generate the credential files? [Y/n] ')

    echo
    CREATE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to create and push the el-CICD Git repositories? [Y/n] ')

    if [[ ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        echo
        local HOST_DOMAIN='github.com'
        echo 'NOTE: Only GitHub is supported as a remote host.'
        read -p "Enter Git host domain (default's to '${HOST_DOMAIN}' left blank): " GIT_HOST_DOMAIN
        GIT_HOST_DOMAIN=${GIT_HOST_DOMAIN:-${HOST_DOMAIN}}

        read -p "Enter Git user/organization: " GIT_USER
        if [[ -z ${GIT_USER} ]]
        then
            echo "ERROR: MUST ENTER A GIT USER"
            exit 1
        fi
        read -p "Enter Git user/organization email: " GIT_USER_EMAIL

        local API_DOMAIN='api.github.com'
        read -p "Enter Git host REST API domain (default's to '${API_DOMAIN}' left blank): " GIT_API_DOMAIN
        GIT_API_DOMAIN=${GIT_API_DOMAIN:-${API_DOMAIN}}
    fi

    if [[ ${GENERATE_CRED_FILES} == ${_YES} || ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        read -s -p "Enter Git host personal access token:" GIT_REPO_ACCESS_TOKEN

        if [[ -z ${GIT_REPO_ACCESS_TOKEN} ]]
        then
            echo "ERROR: MUST ENTER A GIT PERSONAL ACCESS TOKEN"
            exit 1
        fi
        echo
    fi
}

__summarize_and_confirm_dev_setup_info() {
    echo
    echo "SUMMARY:"
    echo

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        echo "CRC WILL be setup.  Login to kubeadmin will be automated."
    else
        echo "CRC will NOT be setup."
    fi

    if [[ ${INSTALL_DOCKER_REGISTRY} == ${_YES} ]]
    then
        echo -n "An image registry WILL be installed on your cluster WITH"
        if [[ ${SETUP_DOCKER_REGISTRY_NFS} != ${_YES} ]]
        then
            echo -n "OUT"
        fi
        echo " an NFS share."

        if [[ ${SETUP_CRC} != ${_YES} ]]
        then
            echo "You MUST be currently logged into a cluster as a cluster admin."
        fi
    else
        echo "An image registry will NOT be installed on your cluster."
    fi

    if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
    then
        echo 'Credential files WILL be (re)generated.'
    else
        echo 'Credential files will NOT be (re)generated.'
    fi

    if [[ ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        echo "el-CICD Git repositories WILL be intialized and pushed to ${GIT_API_DOMAIN}, if necessary"
    else
        echo "el-CICD Git repositories will NOT be intialized."
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
    ${CRC_EXEC} setup <<< 'y'

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
        if [[ -z $(oc get groups ${GROUP} --no-headers --ignore-not-found) ]]
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
        oc create -f ${SCRIPTS_RESOURCES_DIR}/master.key
        oc delete pod --ignore-not-found -n kube-system -l name=sealed-secrets-controller
    else
        echo "Sealed Secrets master key found. Apply manually if still required.  Skipping..."
    fi
}

__create_docker_registry_nfs_share() {
    echo
    if [[ ! -d ${DOCKER_REGISTRY_DATA_NFS_DIR} || -z $(printf "%s\n" "${SUDO_PWD}" | sudo --stdin exportfs | grep ${DOCKER_REGISTRY_DATA_NFS_DIR}) ]]
    then
        echo "Creating NFS share on host for Nexus, if necessary: ${DOCKER_REGISTRY_DATA_NFS_DIR}"
        if [[ -z $(cat /etc/exports | grep ${DOCKER_REGISTRY_DATA_NFS_DIR}) ]]
        then
            printf "%s\n" "${SUDO_PWD}" | sudo -E --stdin bash -c 'echo "${DOCKER_REGISTRY_DATA_NFS_DIR} *(rw,sync,all_squash,insecure)" | sudo tee -a /etc/exports'
        fi

        printf "%s\n" "${SUDO_PWD}" | sudo --stdin mkdir -p ${DOCKER_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin chown -R nobody:nobody ${DOCKER_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin chmod 777 ${DOCKER_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin exportfs -a
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin systemctl restart nfs-server.service
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

        local LOCAL_NFS_IP=$(ip -j route get 8.8.8.8 | jq -r '.[].prefsrc')
        sed -e "s/%LOCAL_NFS_IP%/${LOCAL_NFS_IP}/" ${SCRIPTS_RESOURCES_DIR}/docker-registry-pv-template.yml | oc create -f -
    fi

    __generate_deployments

    __register_insecure_registries

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
    local REGISTRY_NAMES=$(echo ${DOCKER_REGISTRY_USER_NAMES} | tr ':' ' ')
    for REGISTRY_NAME in ${REGISTRY_NAMES}
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
}

__register_insecure_registries() {
    echo
    if [[ -z $(oc describe image.config.openshift.io/cluster | grep 'Insecure Registries') ]]
    then
        echo "Adding array for whitelisting insecure registries."
        oc patch image.config.openshift.io/cluster --type=merge -p='{"spec":{"registrySources":{"insecureRegistries":[]}}}'
    else
        echo "Array for whitelisting insecure image registries already exists.  Skipping..."
    fi

    local REGISTRY_NAMES=$(echo ${DOCKER_REGISTRY_USER_NAMES} | tr ':' ' ')
    for REGISTRY_NAME in ${REGISTRY_NAMES}
    do
        local HOST_DOMAIN=${REGISTRY_NAME}-docker-registry.${CLUSTER_WILDCARD_DOMAIN}

        oc get image.config.openshift.io/cluster -o yaml | grep -v "\- ${HOST_DOMAIN}" | oc apply -f -

        echo "Whitelisting ${HOST_DOMAIN} as an insecure image registry."
        oc patch image.config.openshift.io/cluster --type=json \
            -p='[{"op": "add", "path": "/spec/registrySources/insecureRegistries/-", "value": "'"${HOST_DOMAIN}"'" }]'
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
    echo "Creating ${GIT_USER} access token file"
    echo ${GIT_REPO_ACCESS_TOKEN} > ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}

    echo
    CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        echo "Creating the image repository access token file for ${ENV} environment"
        echo ${DOCKER_REGISTRY_ADMIN} > $(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})
    done
}

__init_el_cicd_repos() {
    local ALL_EL_CICD_DIRS=$(echo "${EL_CICD_REPO_DIRS}:${EL_CICD_DOCS}:${EL_CICD_TEST_PROJECTS}" | tr ':' ' ')
    for EL_CICD_DIR in ${ALL_EL_CICD_DIRS}
    do
        __create_git_repo ${EL_CICD_DIR}
    done
}

__create_git_repo() {
    local GIT_REPO_DIR=${1}
    local GIT_COMMAND="git -C ${EL_CICD_HOME}/${GIT_REPO_DIR}"
    if [[ ! -d ${EL_CICD_HOME}/${GIT_REPO_DIR}/.git ]]
    then
        git init ${EL_CICD_HOME}/${GIT_REPO_DIR}
        ${GIT_COMMAND} add -A 
        ${GIT_COMMAND} commit -am 'Initial commit of el-CICD repositories by bootstrap script'
        ${GIT_COMMAND} config --global user.name ${GIT_USER}
        ${GIT_COMMAND} config --global user.email ${GIT_USER_EMAIL}
    else
        echo "Repo ${GIT_REPO_DIR} already initialized.  Skipping..."
    fi

    __create_remote_github_repo ${GIT_REPO_DIR}

    ${GIT_COMMAND} remote add origin git@${GIT_HOST_DOMAIN}:${GIT_USER}/${GIT_REPO_DIR}.git
    ${GIT_COMMAND} branch ${EL_CICD_BRANCH_NAME}

    ${GIT_COMMAND} \
        -c credential.helper="!creds() { echo username=${GIT_USER}; echo password=${GIT_REPO_ACCESS_TOKEN}; }; creds" \
        push -u origin ${EL_CICD_BRANCH_NAME}
}

__create_remote_github_repo() {
    local GIT_REPO_DIR=${1}

    echo "Creating remote ${GIT_REPO_DIR} repository"
    local GIT_JSON_POST=$(jq -n --arg GIT_REPO_DIR "${GIT_REPO_DIR}" '{"name":$GIT_REPO_DIR}')

    REMOTE_GIT_DIR_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        -u :${GIT_REPO_ACCESS_TOKEN} \
        -H "Accept: application/vnd.github.v3+json"  \
        https://${GIT_API_DOMAIN}/user/repos \
        -d "${GIT_JSON_POST}")
    if [[ ${REMOTE_GIT_DIR_EXISTS} == 201 ]]
    then
        echo "Created ${GIT_USER}/${GIT_REPO_DIR} at ${GIT_API_DOMAIN}"
    else
        echo "ERROR: DID NOT create ${GIT_USER}/${GIT_REPO_DIR} at ${GIT_API_DOMAIN}"
        echo "Check your Git credentials and whether the repo already exists."
    fi
}


__set_config(){
    printf "%s\n" "${SUDO_PWD}" | sudo --stdin sed -i "s/^\($1\s*=\s*\).*\$/\1$2/" $3
}