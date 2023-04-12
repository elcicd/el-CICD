#!/bin/bash
set -e
EL_CICD_KUSTOMIZE_DIR=${1}

cat <&0 > ${EL_CICD_KUSTOMIZE_DIR}/helm-all.yaml

kustomize edit add resource ${EL_CICD_KUSTOMIZE_DIR}/helm-all.yaml
kustomize build ${EL_CICD_KUSTOMIZE_DIR}

set +e