#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

# $1 -> Namespace
_create_onboarding_automation_server() {
    echo
    oc new-app jenkins-persistent -p MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
                                  -p VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
                                  -p DISABLE_ADMINISTRATIVE_MONITORS=${JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                  -p JENKINS_IMAGE_STREAM_TAG=jenkins:latest \
                                  -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                  -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                  -e TRY_UPGRADE_IF_NO_MARKER=true \
                                  -e PLUGINS_FORCE_UPGRADE=true \
                                  -n ${1}

    echo
    echo "'jenkins' service sccount needs cluster-admin permissions for managing multiple projects and permissions"
    oc adm policy add-cluster-role-to-user -z jenkins cluster-admin -n ${1}

    echo
    echo -n "Waiting for Jenkins to be ready."
    sleep 3
    until
        sleep 3 && oc get pods -l name=jenkins -n ${EL_CICD_NON_PROD_MASTER_NAMEPACE} | grep "1/1"
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
        until
            !(oc project ${1} > /dev/null 2>&1)
        do
            echo -n '.'
            sleep 1
        done
        sleep 10
    fi
}