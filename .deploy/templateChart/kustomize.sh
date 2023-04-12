#!/bin/bash
set -e
EL_CICD_KUSTOMIZE_DIR=${1}

cd ${EL_CICD_KUSTOMIZE_DIR}

cat <&0 > ./helm-all.yaml

kustomize edit add resource helm-all.yaml
kustomize build .

set +e