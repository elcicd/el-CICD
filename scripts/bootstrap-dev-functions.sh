#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

__bootstrap_dev_environment() {
    echo
    echo "${DEV_SETUP_WELCOME_MSG}"

    __gather_dev_setup_info

    __summarize_and_confirm_dev_setup_info

    __set_config_value CLUSTER_WILDCARD_DOMAIN ${CLUSTER_WILDCARD_DOMAIN} "${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}"

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        __bootstrap_clean_crc

        __additional_cluster_config
    fi

    if [[ ${INSTALL_IMAGE_REGISTRY} == ${_YES} ]]
    then
        _remove_image_registry

        __setup_image_registries
    fi

    if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
    then
        __create_credentials
    fi

    if [[ ${CREATE_GIT_REPOS} == ${_YES} && ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        __init_el_cicd_repos
    fi

    echo
    echo "el-CICD Development environment setup complete."
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

    if [[ ${SETUP_CRC} != ${_YES} ]]
    then
        read -p 'Enter Cluster wildcard domain (leave blank if using a currently running CRC instance): ' TEMP_CLUSTER_WILDCARD_DOMAIN
        CLUSTER_WILDCARD_DOMAIN=${TEMP_CLUSTER_WILDCARD_DOMAIN:-${CLUSTER_WILDCARD_DOMAIN}}
    fi

    echo
    INSTALL_IMAGE_REGISTRY=$(_get_yes_no_answer 'Do you wish to install the development image registry on your cluster? [Y/n] ')
    if [[ ${INSTALL_IMAGE_REGISTRY} == ${_YES} ]]
    then
        SETUP_IMAGE_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to setup an NFS share for your image registry (only needed for developers)? [Y/n] ')

        if [[ ${SETUP_IMAGE_REGISTRY_NFS} == ${_YES} ]]
        then
            read -s -p "Sudo credentials required: " SUDO_PWD
            set -e
            printf "%s\n" "${SUDO_PWD}" | sudo -k -p '' -S echo 'verified'
            set +e
        fi
    else
        echo 'IF NOT ALREADY DONE, proper values for your chosen image registry must be set in the el-CICD configuration files.'
        echo 'See el-CICD operational documentation for information on how to configure the image registry values per SDLC environment.'
    fi

    echo
    GENERATE_CRED_FILES=$(_get_yes_no_answer 'Do you wish to (re)generate the credential files? [Y/n] ')

    if [[ ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        echo
        CREATE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to create and push the el-CICD Git repositories? [Y/n] ')

        if [[ ${CREATE_GIT_REPOS} == ${_YES} && ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
        then
            echo
            local HOST_DOMAIN='github.com'
            echo 'NOTE: Only GitHub is supported as a remote host.'
            read -p "Enter Git host domain (default's to '${HOST_DOMAIN}' if left blank): " GIT_HOST_DOMAIN
            GIT_HOST_DOMAIN=${GIT_HOST_DOMAIN:-${HOST_DOMAIN}}

            local API_DOMAIN='api.github.com'
            read -p "Enter Git host REST API domain (default's to '${API_DOMAIN}' if left blank): " GIT_API_DOMAIN
            GIT_API_DOMAIN=${GIT_API_DOMAIN:-${API_DOMAIN}}

            read -p "Enter Git user/organization: " EL_CICD_ORGANIZATION
            if [[ -z ${EL_CICD_ORGANIZATION} ]]
            then
                echo "ERROR: MUST ENTER A GIT USER"
                exit 1
            fi
            read -p "Enter Git user/organization email: " EL_CICD_ORGANIZATION_EMAIL
        fi
    fi

    if [[ ${GENERATE_CRED_FILES} == ${_YES} || ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        if [[ ${GENERATE_CRED_FILES} == ${_YES} ]]
        then
            read -s -p "Enter Git host personal access token for ${EL_CICD_ORGANIZATION}:" GIT_REPO_ACCESS_TOKEN
            echo
        else
            GIT_REPO_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE})
        fi

        local TOKEN_TEST_RESULT=$(curl -s -u :${GIT_REPO_ACCESS_TOKEN} https://${EL_CICD_GIT_API_URL}/user | jq -r '.login')
        if [[ ${TOKEN_TEST_RESULT} != ${EL_CICD_ORGANIZATION} ]]
        then
            echo "ERROR: INVALID GIT TOKEN"
            echo "A valid git personal access token for [${EL_CICD_ORGANIZATION}] must be provided when generating credentials and/or Git repositories"
            echo "EXITING..."
            exit 1
        else
            echo "Git token verified."
        fi
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
        echo 'CRC will NOT be setup.'
    fi
    echo "The cluster wildcard domain is: ${CLUSTER_WILDCARD_DOMAIN}"

    if [[ ${INSTALL_IMAGE_REGISTRY} == ${_YES} ]]
    then
        echo -n "An image registry WILL be installed on your cluster WITH"
        if [[ ${SETUP_IMAGE_REGISTRY_NFS} != ${_YES} ]]
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
        echo "el-CICD Git repositories WILL be intialized and pushed to ${EL_CICD_ORGANIZATION} at ${GIT_API_DOMAIN} if necessary."
    else
        echo "el-CICD Git repositories will NOT be intialized."
    fi

    echo "Git token verified against ${EL_CICD_GIT_API_URL}/${EL_CICD_ORGANIZATION}."

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

__create_image_registry_nfs_share() {
    echo
    if [[ ! -d ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} || -z $(printf "%s\n" "${SUDO_PWD}" | sudo --stdin exportfs | grep ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}) ]]
    then
        echo "Creating NFS share on host for developer image registries, if necessary: ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}"
        if [[ -z $(cat /etc/exports | grep ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}) ]]
        then
            printf "%s\n" "${SUDO_PWD}" | sudo -E --stdin bash -c "echo '${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR} *(rw,sync,all_squash,insecure)' | sudo tee -a /etc/exports"
        fi

        printf "%s\n" "${SUDO_PWD}" | sudo --stdin mkdir -p ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin chown -R nobody:nobody ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin chmod 777 ${DEMO_IMAGE_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin exportfs -a
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin systemctl restart nfs-server.service
    else
        echo "Developer image registries' NFS Share found.  Skipping..."
    fi
}

__setup_image_registries() {
    set -e
    oc new-project ${DEMO_IMAGE_REGISTRY}

    if [[ ${SETUP_IMAGE_REGISTRY_NFS} == ${_YES} ]]
    then
        __create_image_registry_nfs_share

        local LOCAL_NFS_IP=$(ip -j route get 8.8.8.8 | jq -r '.[].prefsrc')
        sed -e "s/%LOCAL_NFS_IP%/${LOCAL_NFS_IP}/" \
            -e "s/%DEMO_IMAGE_REGISTRY%/${DEMO_IMAGE_REGISTRY}/" \
            ${SCRIPTS_RESOURCES_DIR}/${DEMO_IMAGE_REGISTRY}-pv-template.yml | oc create -f -
    fi

    __register_insecure_registries

    __generate_deployments
    set +e

    echo
    echo 'Docker Registry is up!'
    sleep 2
}

__generate_deployments() {
    local TMP_DIR=/tmp/${DEMO_IMAGE_REGISTRY}
    mkdir -p ${TMP_DIR}

    local REGISTRY_NAMES=$(echo ${DEMO_IMAGE_REGISTRY_USER_NAMES} | tr ':' ' ')
    for REGISTRY_NAME in ${REGISTRY_NAMES}
    do
        local TMP_FILE=${TMP_DIR}/${DEMO_IMAGE_REGISTRY}-${REGISTRY_NAME}-tmp.yml
        local OUTPUT_FILE=${TMP_DIR}/${DEMO_IMAGE_REGISTRY}-${REGISTRY_NAME}.yml

        sed -e "s/%DEMO_IMAGE_REGISTRY%/${DEMO_IMAGE_REGISTRY}/g"  \
            -e "s/%REGISTRY_NAME%/${REGISTRY_NAME}/g"  \
            -e "s/%CLUSTER_WILDCARD_DOMAIN%/${CLUSTER_WILDCARD_DOMAIN}/g" \
            ${SCRIPTS_RESOURCES_DIR}/${DEMO_IMAGE_REGISTRY}-template.yml > ${TMP_FILE}

        if [[ ${SETUP_IMAGE_REGISTRY_NFS} == ${_YES} ]]
        then
            local PATCH=$(sed -e "s/%DEMO_IMAGE_REGISTRY%/${DEMO_IMAGE_REGISTRY}/g" \
                              -e "s/%REGISTRY_NAME%/${REGISTRY_NAME}/g" \
                              ${SCRIPTS_RESOURCES_DIR}/demo-nfs-deployment.patch)
            oc patch --local -f "${TMP_FILE}" -p "${PATCH}" -o yaml > ${OUTPUT_FILE}
            awk '/^apiVersion:.*/ { print "---" } 1' ${OUTPUT_FILE} > ${TMP_FILE}
        fi

        local HTPASSWD=$(htpasswd -Bbn elcicd${REGISTRY_NAME} ${DEMO_IMAGE_REGISTRY_USER_PWD})
        echo '---' >> ${TMP_FILE}
        oc create secret generic ${REGISTRY_NAME}-auth-secret --dry-run=client --from-literal=htpasswd=${HTPASSWD} \
            -n ${DEMO_IMAGE_REGISTRY} -o yaml >> ${TMP_FILE}

        mv ${TMP_FILE} ${OUTPUT_FILE}
    done

    echo
    oc create -f ${TMP_DIR} -n ${DEMO_IMAGE_REGISTRY}
    rm -rf ${TMP_DIR}

    echo
    local DCS="$(oc get deploy -o 'custom-columns=:.metadata.name' -n ${DEMO_IMAGE_REGISTRY} | xargs)"
    for DC in ${DCS}
    do
        echo
        oc rollout status deploy/${DC} -n ${DEMO_IMAGE_REGISTRY}
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

    local REGISTRY_NAMES=$(echo ${DEMO_IMAGE_REGISTRY_USER_NAMES} | tr ':' ' ')
    for REGISTRY_NAME in ${REGISTRY_NAMES}
    do
        local HOST_DOMAIN=${REGISTRY_NAME}-${DEMO_IMAGE_REGISTRY}.${CLUSTER_WILDCARD_DOMAIN}

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
    echo "Creating ${EL_CICD_ORGANIZATION} access token file: ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}"
    echo ${GIT_REPO_ACCESS_TOKEN} > ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}

    CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        echo
        echo "Creating the image repository access token file for ${ENV} environment:"
        echo "$(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})"
        echo ${DEMO_IMAGE_REGISTRY_USER_PWD} > $(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})
    done
}

__init_el_cicd_repos() {
    __set_config_value EL_CICD_ORGANIZATION ${EL_CICD_ORGANIZATION} "${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}"
    __set_config_value EL_CICD_GIT_DOMAIN ${GIT_HOST_DOMAIN} "${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}"
    __set_config_value EL_CICD_GIT_API_URL ${GIT_API_DOMAIN} "${CONFIG_REPOSITORY}/${ROOT_CONFIG_FILE}"

    find ${CONFIG_REPOSITORY}/project-defs/*.yml -type f -exec sed -i "s/scmOrganization:.*$/scmOrganization: ${EL_CICD_ORGANIZATION}/" {} \;
    find ${CONFIG_REPOSITORY}/project-defs/*.yml -type f -exec sed -i "s/scmHost:.*$/scmHost: ${GIT_HOST_DOMAIN}/" {} \;
    find ${CONFIG_REPOSITORY}/project-defs/*.yml -type f -exec sed -i "s/scmRestApiHost:.*$/scmRestApiHost: ${GIT_API_DOMAIN}/" {} \;

    local ALL_EL_CICD_DIRS=$(echo "${EL_CICD_REPO_DIRS}:${EL_CICD_DOCS}:${EL_CICD_TEST_PROJECTS}" | tr ':' ' ')
    for EL_CICD_DIR in ${ALL_EL_CICD_DIRS}
    do
        __create_git_repo ${EL_CICD_DIR}
    done
}

__create_git_repo() {
    local GIT_REPO_DIR=${1}

    echo
    echo "CREATING REMOTE REPOSITORY: ${GIT_REPO_DIR}"

    local GIT_COMMAND="git -C ${EL_CICD_HOME}/${GIT_REPO_DIR}"
    if [[ ! -d ${EL_CICD_HOME}/${GIT_REPO_DIR}/.git ]]
    then
        git init ${EL_CICD_HOME}/${GIT_REPO_DIR}
        ${GIT_COMMAND} add -A 
        ${GIT_COMMAND} commit -am 'Initial commit of el-CICD repositories by bootstrap script'
        ${GIT_COMMAND} config --global user.name ${EL_CICD_ORGANIZATION}
        ${GIT_COMMAND} config --global user.email ${EL_CICD_ORGANIZATION_EMAIL}
    else
        echo "Repo ${GIT_REPO_DIR} already initialized.  Skipping..."
    fi

    __create_remote_github_repo ${GIT_REPO_DIR}

    ${GIT_COMMAND} remote add origin git@${GIT_HOST_DOMAIN}:${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR}.git
    ${GIT_COMMAND} checkout -b  ${EL_CICD_BRANCH_NAME}

    ${GIT_COMMAND} \
        -c credential.helper="!creds() { echo password=${GIT_REPO_ACCESS_TOKEN}; }; creds" \
        push -u origin ${EL_CICD_BRANCH_NAME}
}

__create_remote_github_repo() {
    local GIT_REPO_DIR=${1}

    local GIT_JSON_POST=$(jq -n --arg GIT_REPO_DIR "${GIT_REPO_DIR}" '{"name":$GIT_REPO_DIR}')

    REMOTE_GIT_DIR_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        -u :${GIT_REPO_ACCESS_TOKEN} \
        -H "Accept: application/vnd.github.v3+json"  \
        https://${GIT_API_DOMAIN}/user/repos \
        -d "${GIT_JSON_POST}")
    if [[ ${REMOTE_GIT_DIR_EXISTS} == 201 ]]
    then
        echo "Created ${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR} at ${GIT_API_DOMAIN}"
    else
        echo "ERROR: DID NOT create ${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR} at ${GIT_API_DOMAIN}"
        echo "Check your Git credentials and whether the repo already exists."
    fi
}


__set_config_value(){
    local KEY=${1}
    local NEW_VALUE=${2}
    local FILE=${3}

    sed -i -e "/${KEY}=/ s/=.*/=${NEW_VALUE}/" ${FILE}
}