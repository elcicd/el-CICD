#!/bin/bash
set -ex

PROFILES=$(echo ${1} | tr ',' ' ')
CHART_DIR=$(dirname "${0}")

cd "${CHART_DIR}"
EL_CICD_DEPLOY_FINAL=elcicd-deploy-final.yaml
PROJECT_NAME=$(basename ${CHART_DIR})

BASE=base
EL_CICD_KUSTOMIZE=elcicd-kustomize

HELM_OUT_FILES_PREFIX=helmOut
HELM_OUT_FILES_POSTFIX='%03d.yaml'
HELM_OUT_DELIM='/---/'
HELM_OUT_FILES_COLLECTED=helmOut-all.yaml


__kustomize_project() {
    cat <&0 | csplit - \
        --quiet \
        --prefix ${HELM_OUT_FILES_PREFIX} \
        --suffix-format ${HELM_OUT_FILES_POSTFIX} \
        --elide-empty-files ${HELM_OUT_DELIM} \
        '{*}'
     
    rm -f ${EL_CICD_DEPLOY_FINAL}
    KUSTOMIZE_DIRS=$(find .  -type d -path '*/kustomize' | tr '\n' ' ' )
    for KUST_DIR in ${KUSTOMIZE_DIRS}
    do
        __createBaseDir ${KUST_DIR} &
    done
    wait
    
    for KUST_DIR in ${KUSTOMIZE_DIRS}
    do
        (__createBaseKustomize ${KUST_DIR} && __createProfilesDirs ${KUST_DIR}) &
    done
    wait
    
    KUST_COUNTER=0
    for KUST_DIR in ${KUSTOMIZE_DIRS}
    do
        let KUST_COUNTER=KUST_COUNTER+1
        COMP_FINAL_DEPLOY_YAML=${CHART_DIR}/${KUST_COUNTER}-${EL_CICD_DEPLOY_FINAL}
        echo "# PROFILES -> ${PROFILES}" > ${COMP_FINAL_DEPLOY_YAML}
        (cd ${KUST_DIR}/${EL_CICD_KUSTOMIZE} && kustomize build . >> ${COMP_FINAL_DEPLOY_YAML}) &
    done
    wait
    
    KUST_COUNTER=0
    for KUST_DIR in ${KUSTOMIZE_DIRS}
    do
        let KUST_COUNTER=KUST_COUNTER+1
        cat ./${KUST_COUNTER}-${EL_CICD_DEPLOY_FINAL}
        echo '---'
    done
}

__createBaseDir() {
    KUST_DIR=${1}

    BASE_DIR=${KUST_DIR}/${BASE}
    mkdir -p ${BASE_DIR}
    
    COMP_DIR=$(echo ${KUST_DIR} | sed -E -e 's/[.]|kustomize|//g')
    HELM_OUT_PATTERN="# Source: [\w-]+${COMP_DIR}templates"
    HELM_OUT_FILES=$(grep -l -P "${HELM_OUT_PATTERN}"  ${HELM_OUT_FILES_PREFIX}*.yaml | tr '\n' ' ')
    if [[ -z ${HELM_OUT_FILES} ]]
    then
        echo "ERROR: unable to find '${HELM_OUT_PATTERN}'"
        exit 1
    fi
    
    cat ${HELM_OUT_FILES} > ${BASE_DIR}/${HELM_OUT_FILES_COLLECTED}
    rm -f ${HELM_OUT_FILES}
}

__createBaseKustomize() {
    KUST_DIR=${1}

    BASE_DIR=${KUST_DIR}/${BASE}

    if [[ ! -f ${BASE_DIR}/kustomization.yaml && ! -f ${BASE_DIR}/kustomization.yml ]]
    then
        (cd ${BASE_DIR} && kustomize create --autodetect)
    fi

    (cd ${BASE_DIR} && \
     kustomize edit remove resource ${HELM_OUT_FILES_COLLECTED} && \
     kustomize edit add resource ${HELM_OUT_FILES_COLLECTED})
}

__createProfilesDirs() {
    KUST_DIR=${1}

    local _LAST_DIR=${BASE}
    for PROFILE in ${PROFILES} ${EL_CICD_KUSTOMIZE}
    do
        CURR_DIR=${KUST_DIR}/${PROFILE}
        mkdir -p ${KUST_DIR}/${PROFILE}
        if [[ ! -f ${CURR_DIR}/kustomization.yaml && ! -f ${CURR_DIR}/kustomization.yml ]]
        then
            (cd ${CURR_DIR} && kustomize create --autodetect)
        fi

        (cd ${CURR_DIR} && \
         kustomize edit remove resource ../${_LAST_DIR} && \
         kustomize edit add resource ../${_LAST_DIR})
        _LAST_DIR=${PROFILE}
    done
}

__kustomize_project

set +e
