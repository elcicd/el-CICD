#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

# $1 -> el-CICD Master namespace
_collect_and_verify_bootstrap_actions_with_user() {
    _check_sealed_secrets
    __confirm_update_default_jenkins_image
    __confirm_build_el_cicd_jenkins_image

    echo
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo 'Please read CAREFULLY and verify this information is correct before proceeding.'
    echo 
    if [[ ! -z ${SEALED_SECRET_RELEASE_VERSION} ]]
    then
        echo "Install/Update Sealed Secrets to version? ${INSTALL_KUBESEAL}"
    else
        echo "SEALED SECRETS WILL NOT BE INSTALLED.  A Sealed Secrets version in el-CICD configuration is not defined."
    fi
    echo "Update cluster default Jenkins image? ${UPDATE_JENKINS}"
    echo "Update/build el-CICD Jenkins image? ${UPDATE_EL_CICD_JENKINS}"
    echo "Cluster wildcard Domain? ${CLUSTER_WILDCARD_DOMAIN}"

    DEL_NAMESPACE=$(oc projects -q | grep ${1} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        echo
        echo -n "WARNING: ${1} was found, and will WILL BE DESTROYED AND REBUILT"
    else 
        echo "${1} will be created to host the el-CICD master"
    fi
    if [[ ! -z ${1} ]]
    then
        echo " with the following node selectors:"
        echo "${1}"
    else
        echo
    fi

    echo
    echo "Do you wish to continue? [Yes/No]: "
    CONTINUE='N'
    read CONTINUE
    if [[ ${CONTINUE} != 'Yes' ]]
    then
        echo "You must enter 'Yes' for bootstrap to continue.  Exiting..."
        exit 0
    fi
}

# $1 -> Namespace
# $2 -> Namespace Node Selectors
# $3 -> Jenkins image stream name
# $4 -> Jenkins CASC file name
# $5 -> Jenkins plugin list file name
_run_el_cicd_bootstrap() {
    if [[ ${INSTALL_KUBESEAL} == 'Yes' ]]
    then
        _install_sealed_secrets ${1}
    fi

    if [[ ${UPDATE_JENKINS} == 'Yes' ]]
    then
        oc import-image ${1} -n openshift
    fi

    if [[ ${UPDATE_EL_CICD_JENKINS} == 'Yes' ]]
    then
        _build_el_cicd_jenkins_image ${3} ${4}  ${5}
    fi

    _delete_namespace ${1}

    _create_namespace_with_selectors ${1} ${2}

    _create_onboarding_automation_server ${3} ${1}
}

__confirm_update_default_jenkins_image() {
    UPDATE_JENKINS='N'
    echo -n 'Update cluster default Jenkins image? [Y/n] '
    read -n 1 UPDATE_JENKINS
    echo

    if [[ ${UPDATE_JENKINS} == 'Y' ]]
    then
        UPDATE_JENKINS='Yes'
    else
        UPDATE_JENKINS='No'
    fi
}

__confirm_build_el_cicd_jenkins_image() {
    UPDATE_EL_CICD_JENKINS='N'
    echo -n 'Update/build el-CICD Jenkins image? [Y/n] '
    read -n 1 UPDATE_EL_CICD_JENKINS
    echo

    if [[ ${UPDATE_EL_CICD_JENKINS} == 'Y' ]]
    then
        UPDATE_EL_CICD_JENKINS='Yes'
    else
        UPDATE_EL_CICD_JENKINS='No'
    fi
}

# $1 -> Namespace name
# $2 -> Namespace selectors
_create_namespace_with_selectors() {
    echo
    local SELECTORS=$(echo ${2} | tr -d '[:space:]')
    echo "Creating ${1} with node selectors: ${SELECTORS}"
    oc adm new-project ${1} --node-selector="${SELECTORS}"
}

# $1 -> Jenkins imagestream name
# $2 -> Jenkins yaml configuration as code file
# $3 -> Jenkins plugins txt file
# $4 -> Force Jenkins base image update ('Y' will force update)
_build_el_cicd_jenkins_image() 
    local CONFIRM_UPDATE_JENKINS='N'
    if [[ -z $(oc get is --ignore-not-found ${1} -n openshift) ]]
    then
        echo "FROM ${OCP_IMAGE_REPO}/jenkins" | oc new-build -D - --name ${1} -n openshift
        sleep 10
        oc logs -f bc/${1} -n openshift
        oc delete bc ${1} -n openshift
        CONFIRM_UPDATE_JENKINS='Y'
    fi

    if [[ ${CONFIRM_UPDATE_JENKINS} == 'N' && ${4} != 'Y' ]]
    then
        echo
        echo -n 'Update non-prod el-CICD Jenkins image? [Y/n] '
        read -t 10 -n 1 CONFIRM_UPDATE_JENKINS
        echo
    fi

    if [[ ${CONFIRM_UPDATE_JENKINS} == 'Y' || ${4} == 'Y' ]]
    then
        echo
        echo "Updating el-CICD Jenkins image ${1}"
        echo
        if [[ ! -n $(oc get bc ${1} --ignore-not-found -n openshift) ]]
        then
            oc new-build --name ${1} --binary=true --strategy=docker -n openshift
        fi

        cp ${TEMPLATES_DIR}/Dockerfile.jenkins-template ${CONFIG_REPOSITORY_JENKINS}/Dockerfile
        sed -i -e "s|%OCP_IMAGE_REPO%|${OCP_IMAGE_REPO}|;" \
               -e  "s/%FROM_IMAGE%/${1}/;" \
               -e  "s/%JENKINS_CONFIGURATION_FILE%/${2}/g;" \
               -e  "s/%JENKINS_PLUGINS_FILE%/${3}/g" \
            ${CONFIG_REPOSITORY_JENKINS}/Dockerfile

        oc start-build ${1} --from-dir=${CONFIG_REPOSITORY_JENKINS} --wait --follow -n openshift
        rm -f ${CONFIG_REPOSITORY_JENKINS}/Dockerfile
    fi
}

# $1 -> Jenkins imagestream name
# $3 -> Namespace
_create_onboarding_automation_server() {
    echo
    oc new-app jenkins-persistent -p MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
                                  -p VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
                                  -p DISABLE_ADMINISTRATIVE_MONITORS=${JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                  -p JENKINS_IMAGE_STREAM_TAG=${1}:latest \
                                  -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                  -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                  -e TRY_UPGRADE_IF_NO_MARKER=true \
                                  -n ${3}

    echo
    echo "'jenkins' service account needs cluster-admin permissions for managing multiple projects and permissions"
    oc adm policy add-cluster-role-to-user -z jenkins cluster-admin -n ${3}

    echo
    echo -n "Waiting for Jenkins to be ready."
    until
        sleep 3 && oc get pods --ignore-not-found -l name=jenkins -n ${3} | grep "1/1"
    do
        echo -n '.'
    done

    echo
    echo 'Jenkins up, sleep for 10 more seconds to make sure server REST api is ready'
    sleep 10
}

# $1 -> Namespace
_delete_namespace() {
    oc delete project ${1}
    echo -n "Confirming deletion"
    until
        !(oc project ${1} > /dev/null 2>&1)
    do
        echo -n '.'
        sleep 1
    done
    echo
    echo "Namespace ${1} deleted.  Sleep 10s to confirm cleanup."
    sleep 10
}

# $1 -> Namespace
# $2 -> If 'true' only build the base agent rather than all
_build_jenkins_agents() {
    if [[ ${JENKINS_SKIP_AGENT_BUILDS} != 'true' ]]
    then
        HAS_BASE_AGENT=$(oc get --ignore-not-found is jenkins-agent-el-cicd-${JENKINS_AGENT_DEFAULT} -n openshift -o jsonpath='{.metadata.name}')
        if [[ -z ${HAS_BASE_AGENT} ]]
        then
            echo
            echo "Creating Jenkins Agents"
            oc start-build create-all-jenkins-agents -e BUILD_BASE_ONLY=${2} -n ${1}
            echo "Started 'create-all-jenkins-agents' job on Non-prod Onboarding Automation Server"
        else 
            echo
            echo "Base agent found: to manually rebuild Jenkins Agents, run the 'create-all-jenkins-agents' job"
        fi
    else
        echo
        echo "JENKINS_SKIP_AGENT_BUILDS=true.  Jenkins agent builds skipped."
    fi
}