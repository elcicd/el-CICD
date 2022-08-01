#!/bin/bash
set -e
cd "$(dirname ${0})"

cat <&0 > ./resources/all.yaml

awk '/__VALUES_END__/{flag=0} flag; /__VALUES_START__/{flag=1}' ./resources/all.yaml > values.yaml
sed -i '/__VALUES_START__/,/__VALUES_END__/d' ./resources/all.yaml

COMMENTS=$(awk '/# EXCLUDED/||/# Profiles:/ {print $0}' ./resources/all.yaml)
RENDERED=$(awk '/# Rendered ->/ {print $0}' ./resources/all.yaml)

helm template kustomize -f values.yaml . > kustomization.yaml
kustomize build .
echo "${COMMENTS}"
echo '---'
echo "${RENDERED}"
echo '---'
set +e