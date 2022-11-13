#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_refresh_non_prod_credentials() {
    set -e
    rm -rf ${SECRET_FILE_TEMP_DIR}
    mkdir -p ${SECRET_FILE_TEMP_DIR}

    echo
    echo "Adding read only deploy key for el-CICD"
    _push_deploy_key_to_github el-CICD ${EL_CICD_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    echo
    echo "Adding read only deploy key for el-CICD-config"
    _push_deploy_key_to_github el-CICD-config ${EL_CICD_CONFIG_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    
    echo
    echo 'Pushing OKD TOKEN to Jenkins'
    local SA_SECRET_NAME=$(oc get secrets -o custom-columns=:.metadata.name -n ${ONBOARDING_MASTER_NAMESPACE} | grep -m 1 jenkins-token)
    local SA_TOKEN="$(oc get secrets ${SA_SECRET_NAME} -o custom-columns=:.data.token -n ${ONBOARDING_MASTER_NAMESPACE} | tr -d '[:space:]')"
    echo ${SA_TOKEN} | base64 -d > ${SECRET_FILE_TEMP_DIR}/${SA_SECRET_NAME}
    _push_access_token_to_jenkins ${JENKINS_URL} ${JENKINS_ACCESS_TOKEN_ID} ${SECRET_FILE_TEMP_DIR}/${SA_SECRET_NAME}

    echo
    echo 'Pushing el-CICD git site wide READ/WRITE token to Jenkins'
    _push_access_token_to_jenkins  ${JENKINS_URL} ${SCM_ADMIN_ACCESS_TOKEN_ID} ${EL_CICD_SCM_ADMIN_ACCESS_TOKEN_FILE}

    echo
    echo 'Pushing el-CICD git READ ONLY private key to Jenkins'
    _push_ssh_creds_to_jenkins ${JENKINS_URL} ${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    echo
    echo 'Pushing el-CICD-config git READ ONLY private key to Jenkins'
    _push_ssh_creds_to_jenkins ${JENKINS_URL} ${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID} ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    echo
    CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV}"
    echo "Creating the image repository pull secrets for each environment: ${CICD_ENVIRONMENTS}"
    # for ENV in ${CICD_ENVIRONMENTS}
    # do
    #     _create_env_image_registry_secret ${ENV} ${ONBOARDING_MASTER_NAMESPACE}
    # done
    _create_env_image_registry_secrets

    echo
    echo "Pushing the image repository access tokens for each environment to Jenkins: ${CICD_ENVIRONMENTS}"
    # for ENV in ${CICD_ENVIRONMENTS}
    # do
    #     ACCESS_TOKEN_ID=$(eval echo \${${ENV}${IMAGE_REGISTRY_ACCESS_TOKEN_ID_POSTFIX}})
    #     SECRET_TOKEN_FILE=$(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})

    #     echo
    #     echo "Pushing ${ENV} image repo access token to Jenkins"
    #     _push_access_token_to_jenkins ${JENKINS_URL} ${ACCESS_TOKEN_ID} ${SECRET_TOKEN_FILE}
    # done

    echo
    echo "Creating ${EL_CICD_BUILD_SECRETS_NAME} secret containing el-CICD build secret(s) in ${ONBOARDING_MASTER_NAMESPACE}"
    oc delete secret --ignore-not-found ${EL_CICD_BUILD_SECRETS_NAME} -n ${ONBOARDING_MASTER_NAMESPACE}
    sleep 5
    mkdir -p ${BUILD_SECRET_FILE_DIR}
    oc create secret generic ${EL_CICD_BUILD_SECRETS_NAME} --from-file=${BUILD_SECRET_FILE_DIR} -n ${ONBOARDING_MASTER_NAMESPACE}

    _run_custom_credentials_script non-prod

    rm -rf ${SECRET_FILE_TEMP_DIR}

    echo 
    echo 'Non-prod Onboarding Server Credentials Script Complete'
    set +e
}
