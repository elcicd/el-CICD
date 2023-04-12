#!/bin/bash
set -e

cd "$(dirname ${HELM_RESOURCES_FILE})"
cat <&0 > ${HELM_RESOURCES_FILE}

kustomize edit add resource ${HELM_RESOURCES_FILE}
kustomize build .

set +e