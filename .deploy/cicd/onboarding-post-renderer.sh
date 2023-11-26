#!/usr/bin/bash
set -ex

cd $(dirname "${0}")

TEAM_ID=${1}

PROJECT_ID=${2}
PROJECT_YAML=${PROJECT_ID}-helmOut.yaml

TMP_DIR=./${TEAM_ID}-tmp

mkdir ${TMP_DIR}

cd ${TMP_DIR}

cat <&0 > ${PROJECT_YAML}

helm template --set-string PROJECT_ID=${PROJECT_ID},TEAM_ID=${TEAM_ID} \
    -f onboarding-kustomization.yaml project-labels \
    ${elcicd_EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart > kustomization.yaml
    
kustomize build .