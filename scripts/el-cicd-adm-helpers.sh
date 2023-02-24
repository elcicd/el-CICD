#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_load_kubectl_msgs() {

WARNING_MSG=$(cat <<-EOM
===================================================================

${_BOLD}WARNING:${_REGULAR} SUDO AND CLUSTER ADMIN PRIVILEGES REQUIRED WHEN USING THIS UTILITY

ACCESS TO el-CICD ONBOARDING AUTOMATION SERVERS SHOULD BE RESTRICTED TO CLUSTER ADMINS

===================================================================
EOM
)

HELP_MSG=$(cat <<-EOM
Usage: oc el-cicd-adm [OPTION]... [root-config-file]

el-CICD Admin Utility

Options:
    -b,   --bootstrap:         bootstraps an el-CICD Onboarding Server
    -f,   --config-file:       generate el-CICD configuration files, both bootstrap and runtime
    -F,   --lab-config-file:   generate el-CICD configuration files for lab installs, both bootstrap and runtime
    -L,   --setup-lab:         setup a lab instance of el-CICD
    -T,   --tear-down-lab:     tear down a lab instance of el-CICD
    -S,   --start-crc:         start the OpenShift Local lab cluster
    -c,   --onboarding-creds:  refresh an el-CICD Onboarding Server credentials
    -C,   --all-creds:         refresh an el-CICD Onboarding Server credentials and then refresh all CICD server credentials
    -s,   --sealed-secrets:    install/upgrade the Sealed Secret Helm Chart or version
    -j,   --jenkins:           build the el-CICD Jenkins image
    -a,   --agents:            build the el-CICD Jenkins agent images
    -h,   --help:              display this help text and exit

root-config-file:
    file name or path to a root configuration file relative the root of the el-CICD-config directory
EOM
)

DEV_SETUP_WELCOME_MSG=$(cat <<-EOM
Welcome to the el-CICD lab environment setup utility.

The el-CICD lab setup utility will optionally perform one or more of the following:
 - Setup OpenShift Local, if downloaded to the el-CICD home directory and not currently installed
 - Setup up an image registry to mimic an external registry, with or without an NFS share
 - Clone and push all el-CICD and sample project Git repositories into your Git host (only GitHub is supported currently)
 - Install the Sealed Secrets controller onto your cluster

NOTES: Red Hat OpenShift Local may be downloaded from here:
       https://developers.redhat.com/products/openshift-local/overview
EOM
)

DEV_TEAR_DOWN_WELCOME_MSG=$(cat <<-EOM
Welcome to the el-CICD lab environment tear down utility.

Before beginning to tear down your environment, please make sure of the following:
 - Log into lab OKD cluster as cluster admin
 - Have root priveleges on this machine; sudo privileges are required

el-CICD will optionally tear down:
 - OpenShift Local
 - The cluster image registry
 - The NFS image registry directory
 - Remove el-CICD repositories pushed to your Git host
EOM
)

} # _load_kubectl_msgs

_execute_kubectl_el_cicd_adm() {
    set -E

    trap 'ERRO_LINENO=$LINENO' ERR
    trap '_failure' EXIT
    echo
    echo "${WARNING_MSG}"
    
    echo
    echo ${ELCICD_ADM_MSG}

    for COMMAND in ${EL_CICD_ADM_COMMANDS[@]}
    do
        eval ${COMMAND}
    done
}

_failure() {
   ERR_CODE=$?
   set +xv
   if [[  $- =~ e && ${ERR_CODE} != 0 ]]
   then
       echo
       echo "========= ${_BOLD}CATASTROPHIC COMMAND FAIL${_REGULAR} ========="
       echo
       echo "el-CICD EXITED ON ERROR CODE: ${ERR_CODE}"
       echo
       LEN=${#BASH_LINENO[@]}
       for (( INDEX=0; INDEX<$LEN-1; INDEX++ ))
       do
           echo '---'
           echo "FILE: $(basename ${BASH_SOURCE[${INDEX}+1]})"
           echo "  FUNCTION: ${FUNCNAME[${INDEX}+1]}"
           if [[ ${INDEX} > 0 ]]
           then
               echo "  COMMAND: ${FUNCNAME[${INDEX}]}"
               echo "  LINE: ${BASH_LINENO[${INDEX}]}"
           else
               echo "  COMMAND: ${BASH_COMMAND}"
               echo "  LINE: ${ERRO_LINENO}"
           fi
       done
       echo
       echo "======= END CATASTROPHIC COMMAND FAIL ======="
       echo
   fi
}

_confirm_logged_into_cluster() {
    echo
    echo "You must be logged into the cluster."
    echo "el-CICD only confirms you are logged in."
    echo "${_BOLD}DOUBLE-CHECK IT'S THE CORRECT CLUSTER${_REGULAR}."
    echo "Confirming..."
    sleep 2
    oc whoami > /dev/null 2>&1 
    echo "${_BOLD}Confirmed${_REGULAR}"
}

_confirm_continue() {
    echo
    echo -n "Do you wish to continue? [${_YES}/${_NO}]: "
    CONTINUE='N'
    read CONTINUE
    if [[ ${CONTINUE} != ${_YES} ]]
    then
        echo
        echo "You must enter '${_BOLD}${_YES}${_REGULAR}' to continue.  Exiting..."
        exit 0
    fi
}

_get_yes_no_answer() {
    read -p "${1}" -n 1 USER_ANSWER
    >&2 echo

    if [[ ${USER_ANSWER} == 'Y' ]]
    then
        echo ${_YES}
    else
        echo ${_NO}
    fi
}

_compare_ignore_case_and_extra_whitespace() {
    local FIRST=$(echo "${1}" | xargs)
    local SECOND=$(echo "${2}" | xargs)
    if [[ -z $(echo "${FIRST}" | grep --ignore-case "^${SECOND}$") ]]
    then
        echo ${_FALSE}
    else
        echo ${_TRUE}
    fi
}

_is_true() {
    _compare_ignore_case_and_extra_whitespace "${1}" ${_TRUE}
}