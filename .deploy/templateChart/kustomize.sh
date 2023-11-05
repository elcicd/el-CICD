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

set -e
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

        kustomize build ${KUST_DIR} >> ${EL_CICD_DEPLOY_FINAL}
    done
    
    cat ${EL_CICD_DEPLOY_FINAL}
}

set +e