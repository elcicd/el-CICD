#!/usr/bin/bash
set -ex

CICD_DIR=${1}
TEAM_ID=${2}

PROJECT_ID=${3}
PROJECT_YAML=${PROJECT_ID}-helmOut.yaml

TMP_DIR=/tmp/${PROJECT_ID}

mkdir -p ${TMP_DIR}

cd ${TMP_DIR}

cat <&0 > ${PROJECT_YAML}

helm template --set-string PROJECT_ID=${PROJECT_ID},TEAM_ID=${TEAM_ID} \
    -f onboarding-kustomization.yaml project-labels \
    ${elcicd_EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart > kustomization.yaml
    
kustomize edit add resource ${PROJECT_YAML}

kustomize build .