#!/bin/bash
set -e
cd "$(dirname ${0})"
cat <&0 > ./resources/all.yml

awk '/__VALUES_END__/{flag=0} flag; /__VALUES_START__/{flag=1}' ./resources/all.yml > values.yml
sed -i '/__VALUES_START__/,/__VALUES_END__/d' ./resources/all.yml

COMMENTS=$(awk '/# EXCLUDED/||/# Profiles:/ {print $0}' ./resources/all.yml)
RENDERED=$(awk '/# Rendered ->/ {print $0}' ./resources/all.yml)

helm template kustomize -f values.yml . > kustomization.yml
kustomize build .
echo "${COMMENTS}"
echo '---'
echo "${RENDERED}"
echo '---'
set +e