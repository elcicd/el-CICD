#!/bin/bash
set -e

PROFILE=${1}
VARIANT=${2}

cd "$(dirname "$0")"

KUSTOMIZE=kustomize
BASE=base
EL_CICD_KUSTOMIZE=elcicd-kustomize
KUSTOMIZATION=kustomization.yaml

HELM_OUT_FILES_PREFIX=helmOut
HELM_OUT_FILES_POSTFIX='%03d.yaml'
HELM_OUT_DELIM='/---/'

EL_CICD_COMP_ALL=elcicd-comp-all.yaml

EL_CICD_DEPLOY_FINAL=elcicd-deploy-final.yaml

__kustomize() {
    cat <&0 | csplit - \
        --quiet \
        --prefix ${HELM_OUT_FILES_PREFIX} \
        --suffix-format ${HELM_OUT_FILES_POSTFIX} \
        --elide-empty-files ${HELM_OUT_DELIM} \
        '{*}'

    if [[ -d '.deploy' ]]
    then
        __kustomize_component
    else
        __kustomize_project
    fi
}

__kustomize_component() {
    
}


__kustomize_project() {
    cat <&0 | csplit - \
        --quiet \
        --prefix ${HELM_OUT_FILES_PREFIX} \
        --suffix-format ${HELM_OUT_FILES_POSTFIX} \
        --elide-empty-files ${HELM_OUT_DELIM} \
        '{*}'

    rm ${EL_CICD_DEPLOY_FINAL}
    for COMP in $(ls -d charts/*/ | xargs -n 1 basename)
    do
        COMPDIR="./charts/${COMP}"
        KUST_DIR=$(__find_and_create_kust_dir ${COMPDIR})        
        __move_comp_resources ${KUST_DIR}
        
        __create_kustomization ${KUST_DIR}

        kustomize build ${KUST_DIR} >> ${EL_CICD_DEPLOY_FINAL}
    done
}

__find_and_create_kust_dir() {
    COMPDIR=${1}
    
    local KUST_DIR=${COMPDIR}/${KUSTOMIZE}/${BASE}
    if [[ -d ${COMPDIR}/${KUSTOMIZE}/${PROFILE}-${VARIANT} ]]
    then
        local KUST_DIR=${COMPDIR}/${KUSTOMIZE}/${PROFILE}-${VARIANT}
    elif [[ -d ${COMPDIR}/${KUSTOMIZE}/${PROFILE} ]]
    then
        local KUST_DIR=${COMPDIR}/${KUSTOMIZE}/${PROFILE}
    fi
    
    return ${KUST_DIR}
}

__create_kustomization() {
    KUST_DIR=${1}
    
    mkdir -p ${KUST_DIR}
    if [[ ! -f ${KUST_DIR}/${KUSTOMIZATION} ]]
    then
        (cd ${KUST_DIR} && kustomize create --autodetect)
    else
        (cd ${KUST_DIR} && kustomize edit add ${EL_CICD_COMP_ALL})
    fi
}

__move_comp_resources() {
    KUST_DIR=${1}
        
    HELM_OUT_FILES=$(grep -l ${HELM_OUT_FILES_PREFIX}*.yaml -e "Source:.*/${COMP}/")
    cat ${HELM_OUT_FILES} > ${KUST_DIR}/${EL_CICD_COMP_ALL}
    rm ${HELM_OUT_FILES}
}

__kustomize()

set +e