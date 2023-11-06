#!/bin/bash
set -e

PROFILE=${1}
VARIANT=${2}

cd "$(dirname "$0")"

HELM_OUT_FILES_PREFIX=helmOut
HELM_OUT_FILES_POSTFIX='%03d.yaml'
HELM_OUT_DELIM='/---/'

EL_CICD_DEPLOY_FINAL=elcicd-deploy-final.yaml

set -e
__kustomize_project() {
     cat <&0 | csplit - \
        --quiet \
        --prefix ${HELM_OUT_FILES_PREFIX} \
        --suffix-format ${HELM_OUT_FILES_POSTFIX} \
        --elide-empty-files ${HELM_OUT_DELIM} \
        '{*}'

    rm -f ${EL_CICD_DEPLOY_FINAL}
    for COMP in $(ls -d charts/*/ | xargs -n 1 basename)
    do  
        KUST_DIR="./charts/${COMP}/kustomize"
        
        HELM_OUT_FILES=$(grep -l ${HELM_OUT_FILES_PREFIX}*.yaml -e "Source:.*/${COMP}/")
        cat ${HELM_OUT_FILES} | ${KUST_DIR}/kustomize-comp.sh ${PROFILE} >> ${EL_CICD_DEPLOY_FINAL}
        
        rm ${HELM_OUT_FILES}
    done
    rm -f ${HELM_OUT_FILES_PREFIX}*.yaml
    
    cat ${EL_CICD_DEPLOY_FINAL}
}

__kustomize_project

set +e