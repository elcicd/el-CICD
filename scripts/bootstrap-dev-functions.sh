#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

read -r -d '' DEV_SETUP_WELCOME_MSG << EOM
Welcome to the el-CICD setup utility for developing with or on el-CICD.
Before completing the setup, please make sure of the following:

1) Log into an OKD cluster as cluster admin, or you can use Red Hat CodeReady Containers:
   https://developers.redhat.com/products/codeready-containers/overview
   NOTE: el-CICD can setup CodeReady Containers for you if requested.

2) Have a GitHub account and a personal access token with repo and admin:repo_hook privileges ready for use.

3) Have root priveleges on this machine. sudo password is required to complete setup.

el-CICD will perform the necessary setup for running the tutorial or developing with el-CICD:
    - Optionally setup CodeReady Containers, if downloaded to the el-CICD-dev directory and not currently installed.
    - Optionally setup up a Nexus3 image repository to mimic an external repository, with or without an NFS share.
    - Clone and push all el-CICD and demo project Git repositories into your GitHub account.
    - Install the Sealed Secrets controller onto your cluster

NOTE: This utility is idempotent and can be rerun mutliple times.
EOM

__bootstrap_dev_environment() {
    echo
    echo "${DEV_SETUP_WELCOME_MSG}"

    __gather_dev_setup_info

    __summarize_and_confirm_dev_setup_info

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        __bootstrap_clean_crc
    fi

    if [[ ${INSTALL_NEXUS3} == ${_YES} ]]
    then
        __setup_nexus33
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
        echo "CRC can be setup with 8 vcpus, 48 GB of RAM, and 128 GB of disk space."
        echo
        echo "WARNING: el-CICD will completely remove any old CRC installs."
        SETUP_CRC=$(_get_yes_no_answer 'Do you wish to setup CRC? [Y/n] ')
    else
        echo 'WARNING: CRC tar.xz and/or pull-secret.txt not found in el-CICD home directory.  Skipping...'
    fi

    echo
    INSTALL_NEXUS3=$(_get_yes_no_answer 'Do you wish to install Nexus3 on your OKD cluster? [Y/n] ')
    if [[ ${INSTALL_NEXUS3} == ${_YES} ]]
    then
        SETUP_NEXUS_NFS=$(_get_yes_no_answer 'Do you wish to setup an NFS share for Nexus(for developers)? [Y/n] ')
    fi

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
        echo "CRC will be setup."
    else 
        echo "CRC will NOT be setup."
    fi

    if [[ ${INSTALL_NEXUS3} == ${_YES} ]]
    then
        echo -n "Nexus3 will be installed on your cluster WITH"
        if [[ ${SETUP_NEXUS_NFS} != ${_YES} ]]
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
    __remove_existing_crc

    echo
    echo "Extracting CRC tar.xz to ${EL_CICD_HOME}"
    tar -xf ${EL_CICD_HOME}/crc*.tar.xz -C ${EL_CICD_HOME}

    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    echo
    ${CRC_EXEC} stop

    echo rm -rf ${HOME}/.crc

    echo
    ${CRC_EXEC} setup

    echo
    echo "Starting CRC with ${CRC_V_CPU} vCPUs, ${CRC_MEMORY}M memory, and ${CRC_DISK}G disk"
    ${CRC_EXEC} start -c ${CRC_V_CPU} -m ${CRC_MEMORY} -d ${CRC_DISK} -p ${EL_CICD_HOME}/pull-secret.txt

    eval $(${CRC_EXEC} oc-env)
    source <(oc completion ${CRC_SHELL})
}

__create_nexus3_nfs_share() {
    echo
    if [[ ! -d /mnt/nexus-data || -z $(sudo exportfs | grep ${NEXUS_DATA_NFS_DIR}) ]]
    then
        echo 'Creating NFS share on host for Nexus, if necessary: /mnt/nexus-data'
        sudo mkdir -p ${NEXUS_DATA_NFS_DIR}

        if [[ -z $(cat /etc/exports | grep ${NEXUS_DATA_NFS_DIR}) ]]
        then
            echo "${NEXUS_DATA_NFS_DIR} *(rw,sync,all_squash,insecure)" | sudo tee -a /etc/exports
        fi

        sudo chown -R nobody:nobody ${NEXUS_DATA_NFS_DIR}
        sudo chmod 777 ${NEXUS_DATA_NFS_DIR}
        sudo exportfs -a
        sudo systemctl restart nfs-server.service
    else
        echo 'Nexus NFS Share found.  Skipping...'
    fi
}

__setup_nexus33() {
    if [[ ${SETUP_NEXUS_NFS} == ${_YES} ]]
    then
        __create_nexus3_nfs_share

        local NEXUS_K8S_FILE=nexus-nfs-local.yml
    else
        local NEXUS_K8S_FILE=nexus-ephemeral.yml
    fi
    echo
    oc apply -f ${RESOURCES_DIR}/${NEXUS_K8S_FILE}

    echo
    oc rollout status deploy nexus -n nexus
    echo
    echo 'Nexus is up!'
    sleep 2

    __create_nexus3_image_repositories
}

__create_nexus3_image_repositories() {
    CURL_COMMAND="curl -Ss -o /dev/null -w '%{http_code}' -u ${NEXUS_ADMIN}:${NEXUS_ADMIN_PWD} --header 'Content-Type: application/json'"

    NEXUS_URL='http://elcicd-nexus.apps-crc.testing/service/rest/v1'

    curl -X PUT ${NEXUS_URL}/security/anonymous -H 'accept: application/json' -H 'Content-Type: application/json' \
        -d '{ "enabled": false, "userId": "anonymous", "realmName": "NexusAuthorizingRealm" }'

    echo
    echo 'Creating the dev, nonprod, and prod image repositories, if necessary'
    NEXUS_DOCKER_REPO_URL="${NEXUS_URL}/repositories/docker/hosted"
    for REPO in dev nonprod prod
    do
        echo
        if [[ $(eval "${CURL_COMMAND} ${NEXUS_DOCKER_REPO_URL}/${REPO}") != '200' ]]
        then
            if [[ $(eval "${CURL_COMMAND} ${NEXUS_DOCKER_REPO_URL} -d @${RESOURCES_DIR}/${REPO}ImageRepoDef.json") == '201' ]]
            then
                echo "${REPO} repository created"
            else
                echo "WARNING: FAILED TO CREATE ${REPO} REPOSITORY. It could be the default password was changed."
            fi
        else
            echo "${REPO} image repository already exists.  Skipping..."
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
        echo ${NEXUS_ADMIN} > $(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})
    done
}

__set_config(){
    sudo sed -i "s/^\($1\s*=\s*\).*\$/\1$2/" $3
}

__remove_nexus3() {
    oc delete --ignore-not-found -f ${RESOURCES_DIR}/nexus-nfs-local.yml
}

__remove_nexus3_nfs_share() {
    if [[ -d ${NEXUS_DATA_NFS_DIR} ]]
    then
        echo
        echo "Removing ${NEXUS_DATA_NFS_DIR} and delisting it as an NFS share"

        sudo rm -rf ${NEXUS_DATA_NFS_DIR}
        sudo sed -i "\|${NEXUS_DATA_NFS_DIR}|d" /etc/exports
        sudo exportfs -a
        sudo systemctl restart nfs-server.service
    else
        echo
        echo "${NEXUS_DATA_NFS_DIR} not found.  Skipping..."
    fi
}

__remove_existing_crc() {
    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    echo
    echo 'Cleaning up old CRC install'
    if [[ ! -z ${CRC_EXEC} ]]
    then
        ${CRC_EXEC} stop
        ${CRC_EXEC} cleanup
    fi

    echo
    echo 'Removing old CRC installation directories'
    rm -rf ${EL_CICD_HOME}/crc*/
    rm -rf ${HOME}/.crc
}

__remove_dev_environment() {
    echo
    echo 'This utility will remove the CRC and Nexus3 NFS share on your system.'

    echo
    _confirm_continue

    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)
    if [[ ! -z ${CRC_EXEC} ]]
    then
        __remove_existing_crc
    fi

    __remove_nexus3_nfs_share
}