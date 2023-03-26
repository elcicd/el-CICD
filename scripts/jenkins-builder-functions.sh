#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later
_build_el_cicd_jenkins_image() {
    echo
    echo "Updating el-CICD Jenkins image ${JENKINS_IMAGE_NAME}"
    echo
    __init_jenkins_build

    set -e
    podman build --squash \
        --build-arg OKD_VERSION=${OKD_VERSION} \
        --build-arg CONFIG_FILE_PATH=${JENKINS_CONFIG_FILE_PATH} \
        --build-arg CASC_FILE=${JENKINS_CASC_FILE} \
        --build-arg JENKINS_PLUGINS_FILE=${JENKINS_PLUGINS_FILE} \
        -t ${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME} \
        -f ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.jenkins
        
    podman push --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} ${JENKINS_IMAGE_REGISTRY}/${JENKINS_IMAGE_NAME}
    set +e

    rm -rf ${TARGET_JENKINS_BUILD_DIR}
    set +e
}

__init_jenkins_build() {
    rm -rf ${TARGET_JENKINS_BUILD_DIR}

    mkdir -p ${TARGET_JENKINS_BUILD_DIR}

    cp -vRT ${EL_CICD_CONFIG_JENKINS_DIR} ${TARGET_JENKINS_BUILD_DIR}

    if [[ ! -z ${JENKINS_AGENTS_BUILD_DIRS} ]]
    then
        echo
        for BUILD_DIR in $(echo ${JENKINS_AGENTS_BUILD_DIRS} | tr ':' ' ')
        do
            cp -vRT ${BUILD_DIR} ${TARGET_JENKINS_BUILD_DIR}
        done
    fi
}

_build_el_cicd_jenkins_agent_images() {
    if [[ $(_get_bool ${JENKINS_SKIP_AGENT_BUILDS}) != ${_TRUE} ]]
    then
        echo
        echo "Creating Jenkins Agents"
        __init_jenkins_build

        local AGENT_NAMES="${JENKINS_AGENT_DEFAULT} $(echo ${JENKINS_BUILD_AGENT_NAMES} | tr ':' ' ')"

        for AGENT_NAME in ${AGENT_NAMES}
        do
            echo
            echo '==========================================='
            echo
            echo "STARTING JENKINS AGENT BUILD: ${AGENT_NAME}"
            echo
            echo '==========================================='
            echo

            set -e            
            podman build --squash \
                --build-arg JENKINS_IMAGE_REGISTRY=${JENKINS_IMAGE_REGISTRY} \
                -t ${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME} \
                -f ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.${AGENT_NAME}

            podman push --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} ${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME}
            set +e
        done

        rm -rf ${TARGET_JENKINS_BUILD_DIR}
    else
        echo
        echo "JENKINS_SKIP_AGENT_BUILDS is set to 'true'.  Jenkins agent builds skipped."
    fi
}

_base_jenkins_agent_exists() {
    IMAGE_URL=docker://${JENKINS_IMAGE_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT}
    local HAS_BASE_AGENT=$(skopeo inspect --format '{{.Name}}({{.Digest}})' --tls-verify=${JENKINS_IMAGE_REGISTRY_ENABLE_TLS} ${IMAGE_URL} 2> /dev/null)
    if [[ -z ${HAS_BASE_AGENT} ]]
    then
        echo ${_FALSE}
    else
        echo ${_TRUE}
    fi
}

