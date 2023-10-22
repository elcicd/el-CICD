#!/bin/bash

set -x
PROFILES=${1}

cd "$(dirname ${0})"

mkdir -p ${elcicd_BASE_KUSTOMIZE_DIR} ${PROFILES}
cat <&0 > ${elcicd_BASE_KUSTOMIZE_DIR}/${elcicd_PRE_KUST_HELM_FILE}

for OVERLAY_DIR in ${elcicd_BASE_KUSTOMIZE_DIR} ${PROFILES}
do
    cd ${OVERLAY_DIR}
    
    [[ ! -f kustomization.yaml ]] && kustkustomize create --autodetect . 2>/dev/null
    
    [[ ! -z ${LAST_OVERLAY_DIR} ]] && kustomize edit add resource "../${LAST_OVERLAY_DIR}"
    
    LAST_OVERLAY_DIR=${OVERLAY_DIR}
    
    cd ..
done

kustomize build ${elcicd_POST_RENDERER_KUSTOMIZE_DIR} > ${elcicd_POST_KUST_HELM_FILE}

cat ${elcicd_POST_KUST_HELM_FILE}

set +e