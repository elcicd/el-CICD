


TARGET_JENKINS_BUILD_DIR=../jenkins-target

__init_jenkins_build() {
    local BUILD_DIRS=${1}
    mkdir -p ${TARGET_JENKINS_BUILD_DIR}

    cp ${CONFIG_REPOSITORY_JENKINS}/* ${TARGET_JENKINS_BUILD_DIR}

    if [[ ! -z ${BUILD_DIRS} ]]
    then
        for BUILD_DIR in $(echo ${BUILD_DIRS} | tr ':' ' ')
        do
            cp -r ${BUILD_DIR} ${TARGET_JENKINS_BUILD_DIR}
        done
    fi
}

_build_el_cicd_jenkins_image() {
    echo
    echo "Updating el-CICD Jenkins image ${JENKINS_IMAGE_STREAM}"
    echo

    __init_jenkins_build ${JENKINS_BUILD_DIRS}

    if [[ ! -n $(oc get bc ${JENKINS_IMAGE_STREAM} --ignore-not-found -n openshift) ]]
    then
        oc new-build --name ${JENKINS_IMAGE_STREAM} --binary=true --strategy=docker -n openshift
    fi

    cat ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.jenkins-template > ${TARGET_JENKINS_BUILD_DIR}/Dockerfile
    sed -i -e "s|%OCP_IMAGE_REPO%|${OCP_IMAGE_REPO}|;" \
           -e  "s|%CONFIG_PATH%|${EL_CICD_JENKINS_CONTAINER_CONFIG_DIR}|g;" \
           -e  "s/%JENKINS_CONFIGURATION_FILE%/${JENKINS_CASC_FILE}/g;" \
           -e  "s/%JENKINS_PLUGINS_FILE%/${JENKINS_PLUGINS_FILE}/g" \
        ${TARGET_JENKINS_BUILD_DIR}/Dockerfile

    oc start-build ${JENKINS_IMAGE_STREAM} --from-dir=${TARGET_JENKINS_BUILD_DIR} --wait --follow -n openshift

    rm -rf ${TARGET_JENKINS_BUILD_DIR}
}

_build_el_cicd_jenkins_agent_images_image() {
    BUILD_BASE_ONLY=${1}

    __init_jenkins_build ${JENKINS_AGENTS_BUILD_DIRS}

    local AGENT_NAMES=${JENKINS_AGENT_DEFAULT}
    if [[ BUILD_BASE_ONLY != 'true' ]]
    then
        AGENT_NAMES="${AGENT_NAMES} $(echo ${JENKINS_AGENT_NAMES} | tr ':' ' ')"
    fi

    for AGENT_NAME in ${AGENT_NAMES}
    do
        if [[ ! -n $(oc get bc ${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME} --ignore-not-found -n openshift) ]]
        then
            oc new-build --name ${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME} --binary=true --strategy=docker -n openshift
        fi
        echo
        echo "Starting Agent Build: ${AGENT_NAME}"

        cat ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.${AGENT_NAME} > ${TARGET_JENKINS_BUILD_DIR}/Dockerfile
        oc start-build ${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME} --from-dir=${TARGET_JENKINS_BUILD_DIR} --wait --follow -n openshift
    done

    rm -rf ${TARGET_JENKINS_BUILD_DIR}
}

