#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_confirm_wildcard_domain_for_cluster() {
    echo
    echo -n "Confirm the wildcard domain for the cluster: ${CLUSTER_WILDCARD_DOMAIN}? [Y/n] "
    read -n 1 CONFIRM_WILDCARD
    echo
    if [[ ${CONFIRM_WILDCARD} != 'Y' ]]
    then
        echo "CLUSTER_WILDCARD_DOMAIN needs to be properly set in el-cicd-system.config"
        echo
        echo "Exiting"
        exit 1
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

# $1 -> Force Jenkins base image update ('Y' will force update)
_update_base_jenkins() {
    local CONFIRM_UPDATE_JENKINS='N'
    echo -n 'Update base cluster Jenkins image? [Y/n] '
    read -t 10 -n 1 CONFIRM_UPDATE_JENKINS

    if [[ ${CONFIRM_UPDATE_JENKINS} == 'Y' || ${1} == 'Y' ]]
    then
        oc import-image ${1} -n openshift
    fi
}

# $1 -> Jenkins imagestream name
# $2 -> Jenkins yaml configuration as code file
# $3 -> Jenkins plugins txt file
# $4 -> Force Jenkins base image update ('Y' will force update)
_build_el_cicd_jenkins_image() {
    _update_base_jenkins

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
# $2 -> Jenkins yaml configuration as code file
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
                                  -e CASC_JENKINS_CONFIG=/usr/lib/jenkins/${2} \
                                  -n ${3}

    echo
    echo "'jenkins' service sccount needs cluster-admin permissions for managing multiple projects and permissions"
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
    DEL_NAMESPACE=$(oc projects -q | grep ${1} | tr -d '[:space:]')
    if [[ ! -z "${DEL_NAMESPACE}" ]]
    then
        echo
        echo "Found ${DEL_NAMESPACE}"
        echo -n "Confirm deletion of el-CICD master namespace: ${DEL_NAMESPACE}? [Y/n] "
        read -n 1 CONFIRM_DELETE
        echo
        if [[ ${CONFIRM_DELETE} != 'Y' ]]
        then
            echo "Deleting el-CICD non-prod master namespace must be completed to continuing."
            echo "Exiting."
            exit 1
        fi

        oc delete project ${1}
        echo -n "Confirming deletion"
        until
            !(oc project ${1} > /dev/null 2>&1)
        do
            echo -n '.'
            sleep 1
        done
        echo
        echo "Old el-CICD master namespace deleted.  Sleep 10s to confirm cleanup."
        sleep 10
    fi
}