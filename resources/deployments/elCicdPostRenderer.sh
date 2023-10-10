#!/bin/bash
set -e

HELM_YAML=${1}
PROFILE=${2}
VARIANT=${3}

cd "$(dirname "$0")"/kustomize

BASE=base
EL_CICD_KUST_DIR=elcicd-kustomize
KUSTOMIZATION=kustomization.yaml

EL_CICD_HELM_ALL=elcicd-helm-all.yaml

__kustomize() {    
    echo ${HELM_YAML} > ${BASE}/${EL_CICD_HELM_ALL}
    
    __init_kust_dir ${BASE} ${EL_CICD_HELM_ALL}
    
    __init_kust_dir ${PROFILE} ../${BASE}
    
    if [[ ! -z ${VARIANT} ]]
    then
        EL_CICD_RESOURCE_DIR=${PROFILE}-${VARIANT}
        __init_kust_dir ${PROFILE}-${VARIANT} ../${PROFILE}
    else
        EL_CICD_RESOURCE_DIR=${PROFILE}
    fi
    
    (cd ${EL_CICD_KUST_DIR} && kustomize edit add resource ../${EL_CICD_RESOURCE_DIR})
    
    kustomize build ${EL_CICD_KUST_DIR}
}

__init_kust_dir() {
    local KUST_DIR=${1}
    local KUST_RESOURCE=${2}
    
    if [[ ! -f ${KUST_DIR}/${KUSTOMIZATION} ]]
        (cd ${KUST_DIR} && kustomize create --autodetect)
    fi
    (cd ${KUST_DIR} && kustomize edit add resource ${KUST_RESOURCE})
}

__kustomize()

set +e