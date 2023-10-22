#!/bin/bash

PROFILES=${1}

cd "$(dirname ${0})"

mkdir -p ${elcicd_BASE_KUSTOMIZE_DIR} ${PROFILES}
cat <&0 > ${elcicd_BASE_KUSTOMIZE_DIR}/${elcicd_PRE_KUST_HELM_FILE}

for OVERLAY_DIR in ${elcicd_BASE_KUSTOMIZE_DIR} ${PROFILES} ${elcicd_POST_RENDERER_KUSTOMIZE_DIR}
do
    cd ${OVERLAY_DIR}
    
    [[ ! -f kustomization.yaml ]] && kustomize create --autodetect . 2>/dev/null
    
    [[ ! -z ${LAST_OVERLAY_DIR} ]] && (kustomize edit add resource "../${LAST_OVERLAY_DIR}" 2>/dev/null)
    
    LAST_OVERLAY_DIR=${OVERLAY_DIR}
    
    cd ..
done

kustomize build ${elcicd_POST_RENDERER_KUSTOMIZE_DIR}