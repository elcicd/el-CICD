#!/bin/bash
set -e
cd "$(dirname ${0})"
cat <&0 > ./resources/all.yml

COMMENTS=$(awk '/^#.*$/&&!/^# Source:/ {print $0}' ./resources/all.yml)

for DIR in $(find . -mindepth 1 -type d -printf '%f\n')
do
  echo "${DIR}:" >> kustomization.yml
  for KUST_FILE in  $(ls ${DIR}/*.yaml ${DIR}/*.yml ${DIR}/*.json 2>/dev/null || : )
  do
    echo "- ${KUST_FILE}" >> kustomization.yml
  done
done
  
oc kustomize .
echo '---'
echo "$COMMENTS"
set +e