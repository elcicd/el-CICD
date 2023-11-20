#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

GITHUB_REST_API_HEADER='Accept: application/vnd.github.v3+json'

GITHUB_DEPLOY_KEY_JSON='
{
    "title": "%DEPLOY_KEY_TITLE%",
    "key": "%DEPLOY_KEY%",
    "read_only": %READ_ONLY%
}'

GITHUB_WEBHOOK_JSON='
{
  "name": "web",
  "active": true,
  "events": [
    "push"
  ],
  "config": {
    "url": "%JENKINS_WEBOOK_URL%",
    "insecure_ssl": "1"
  }
}'

CURL_COMMAND='curl --retry 9 --retry-all-errors -ksSL --fail-with-body'

EL_CICD_TMP_PREFIX='/tmp/tmp.elcicd'

_delete_scm_repo_deploy_key() {
    local GITHUB_API_HOST=${1}
    local GITHUB_ORG=${2}
    local REPO_NAME=${3}
    local GITHUB_ACCESS_TOKEN=${4}
    local DEPLOY_KEY_TITLE=${5}

    local GITHUB_HEADERS=(-H "Authorization: Bearer ${GITHUB_ACCESS_TOKEN}" -H "${GITHUB_REST_API_HEADER}")
    local EL_CICD_GITHUB_KEYS_URL="https://${GITHUB_API_HOST}/repos/${GITHUB_ORG}/${REPO_NAME}/keys"

    local KEY_ID=$(${CURL_COMMAND} "${GITHUB_HEADERS[@]}" ${EL_CICD_GITHUB_KEYS_URL} | jq ".[] | select(.title  == \"${DEPLOY_KEY_TITLE}\") | .id" 2>/dev/null)
    if [[ "${KEY_ID}" ]]
    then
        ${CURL_COMMAND} -X DELETE "${GITHUB_HEADERS[@]}"  ${EL_CICD_GITHUB_KEYS_URL}/${KEY_ID} | jq 'del(.key)'
        echo "DELETED DEPLOY KEY FROM ${GITHUB_ORG}/${REPO_NAME}: ${KEY_ID}"
    fi
}

_add_scm_repo_deploy_key() {
    local GITHUB_API_HOST=${1}
    local GITHUB_ORG=${2}
    local REPO_NAME=${3}
    local GITHUB_ACCESS_TOKEN=${4}
    local DEPLOY_KEY_TITLE=${5}
    local DEPLOY_KEY_FILE="${6}.pub"

    # READ_ONLY *MUST* be 'false' (case insensitive) to push a read/write key
    local READ_ONLY=${7}
    if [[ ${READ_ONLY,,} != 'false' ]]
    then
        READ_ONLY=true
    fi

    local GITHUB_HEADERS=(-H "Authorization: Bearer ${GITHUB_ACCESS_TOKEN}" -H "${GITHUB_REST_API_HEADER}")
    local EL_CICD_GITHUB_KEYS_URL="https://${GITHUB_API_HOST}/repos/${GITHUB_ORG}/${REPO_NAME}/keys"

    local GITHUB_CREDS_FILE="${EL_CICD_TMP_PREFIX}.$(openssl rand -hex 5)"
    trap "rm -f '${EL_CICD_TMP_PREFIX}.*'" EXIT

    CURRENT_DEPLOY_KEY_JSON=${GITHUB_DEPLOY_KEY_JSON/\%DEPLOY_KEY_TITLE%/${DEPLOY_KEY_TITLE}}
    CURRENT_DEPLOY_KEY_JSON=${CURRENT_DEPLOY_KEY_JSON/\%DEPLOY_KEY%/$(<${DEPLOY_KEY_FILE})}
    CURRENT_DEPLOY_KEY_JSON=${CURRENT_DEPLOY_KEY_JSON/\%READ_ONLY%/${READ_ONLY}}
    echo "${CURRENT_DEPLOY_KEY_JSON}" > ${GITHUB_CREDS_FILE}

    local RESULT=$(${CURL_COMMAND} -X POST "${GITHUB_HEADERS[@]}" -d @${GITHUB_CREDS_FILE} ${EL_CICD_GITHUB_KEYS_URL})

    echo
    RESULT=$(echo ${RESULT} | jq 'del(.key)')
    if [[ "$(echo ${RESULT} | jq '.title // empty')" ]]
    then
        rm -f ${GITHUB_CREDS_FILE}
        echo
        echo "ADDED NEW GITHUB DEPLOY KEY FOR ${GITHUB_ORG}/${REPO_NAME}:"
        echo ${RESULT} | jq .
        echo
    else
        echo "ADDING NEW GITHUB DEPLOY KEY FOR ${GITHUB_ORG}/${REPO_NAME} FAILED:"
        echo ${RESULT} | jq .
        cat ${GITHUB_CREDS_FILE}
        echo
        exit 1
    fi
}

_delete_webhook() {
    local GITHUB_HOST=${1}
    local GITHUB_ORG=${2}
    local REPO_NAME=${3}
    local JENKINS_HOST=${4}
    local PROJECT_ID=${5}
    local MODULE_ID=${6}
    local BUILD_TYPE=${7}
    local WEB_TRIGGER_AUTH_TOKEN=${8}
    local GITHUB_ACCESS_TOKEN=${9}

    local GITHUB_HEADERS=(-H "Authorization: Bearer ${GITHUB_ACCESS_TOKEN}" -H "${GITHUB_REST_API_HEADER}")

    local JENKINS_WEBOOK_URL="${JENKINS_HOST}/job/${PROJECT_ID}/job/${MODULE_ID}-${BUILD_TYPE}?token=${WEB_TRIGGER_AUTH_TOKEN}"

    local HOOKS_URL="https://${GITHUB_HOST}/repos/${GITHUB_ORG}/${REPO_NAME}/hooks/"

    local HOOK_IDS=$(${CURL_COMMAND} "${GITHUB_HEADERS[@]}" ${HOOKS_URL} | jq ".[] | select(.config.url  == ${JENKINS_WEBOOK_URL}) | .id")

    for HOOK_ID in ${HOOK_IDS}
    do
        ${CURL_COMMAND} -X DELETE "${GITHUB_HEADERS[@]}" ${HOOKS_URL}/${HOOK_ID}
        echo
        echo "--> DELETED GITHUB WEBHOOK; ID: ${HOOK_ID}"
    done
}

_add_webhook() {
    local GITHUB_HOST=${1}
    local GITHUB_ORG=${2}
    local REPO_NAME=${3}
    local JENKINS_HOST=${4}
    local PROJECT_ID=${5}
    local MODULE_ID=${6}
    local BUILD_TYPE=${7}
    local WEB_TRIGGER_AUTH_TOKEN=${8}
    local GITHUB_ACCESS_TOKEN=${9}

    local GITHUB_HEADERS=(-H "Authorization: Bearer ${GITHUB_ACCESS_TOKEN}" -H "${GITHUB_REST_API_HEADER}")

    local WEBHOOK_FILE="${EL_CICD_TMP_PREFIX}.$(openssl rand -hex 5)"
    trap "rm -f '${EL_CICD_TMP_PREFIX}.*'" EXIT

    local JENKINS_WEBOOK_URL="${JENKINS_HOST}/job/${PROJECT_ID}/job/${MODULE_ID}-${BUILD_TYPE}?token=${WEB_TRIGGER_AUTH_TOKEN}"
    local CURRENT_WEBHOOK_JSON=${GITHUB_WEBHOOK_JSON/\%JENKINS_WEBOOK_URL%/${JENKINS_WEBOOK_URL}}

    echo ${CURRENT_DEPLOY_KEY_JSON} > ${WEBHOOK_FILE}

    local HOOKS_URL="https://${GITHUB_HOST}/repos/${GITHUB_ORG}/${REPO_NAME}/hooks/"

    local WEBHOOK=$(${CURL_COMMAND} -X POST "${GITHUB_HEADERS[@]}" -d @${WEBHOOK_FILE} ${HOOKS_URL})

    echo
    echo "NEW GITHUB WEBHOOK CREATED; ID:"
    echo ${WEBHOOK} | jq .
    echo

    rm -f ${WEBHOOK_FILE}
}
