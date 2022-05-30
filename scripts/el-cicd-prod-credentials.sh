# create and add sealed secret for read only el-CICD access to project

_refresh_prod_credentials() {
    rm -rf ${SECRET_FILE_TEMP_DIR}
    mkdir -p ${SECRET_FILE_TEMP_DIR}

    echo
    echo "Adding read only deploy key for el-CICD"
    _push_github_public_ssh_deploy_key el-CICD ${EL_CICD_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} 

    echo
    echo "Adding read only deploy key for el-CICD-config"
    _push_github_public_ssh_deploy_key el-CICD-config \
                                    ${EL_CICD_CONFIG_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE} \
                                    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    JENKINS_URL=$(oc get route jenkins -o jsonpath='{.spec.host}' -n ${ONBOARDING_MASTER_NAMESPACE})

    echo
    echo 'Pushing el-CICD git site wide READ/WRITE token to Jenkins'
    _push_access_token_to_jenkins  ${JENKINS_URL} ${GIT_SITE_WIDE_ACCESS_TOKEN_ID} ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE}

    echo
    echo 'Pushing el-CICD git READ ONLY private key to Jenkins'
    _push_ssh_creds_to_jenkins ${JENKINS_URL} ${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID} ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    echo
    echo 'Pushing el-CICD-config git READ ONLY private key to Jenkins'
    _push_ssh_creds_to_jenkins ${JENKINS_URL} ${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID} ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    echo
    CICD_ENVIRONMENTS="${PRE_PROD_ENV} ${PROD_ENV}"
    echo "Creating the image repository pull secrets for each environment: ${CICD_ENVIRONMENTS}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        _create_env_image_registry_secret ${ENV} ${ONBOARDING_MASTER_NAMESPACE}
    done

    echo
    echo "Pushing the image repository access tokens for each environment to Jenkins: ${CICD_ENVIRONMENTS}"
    for ENV in ${CICD_ENVIRONMENTS}
    do
        ACCESS_TOKEN_ID=$(eval echo \${${ENV}${IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}})
        SECRET_TOKEN_FILE=$(eval echo \${${ENV}${PULL_TOKEN_FILE_POSTFIX}})

        echo
        echo "Pushing ${ENV} image repo access tokens per environment to Jenkins"
        _push_access_token_to_jenkins ${JENKINS_URL} ${ACCESS_TOKEN_ID} ${SECRET_TOKEN_FILE}
    done

    _run_custom_credentials_script prod

    rm -rf ${SECRET_FILE_TEMP_DIR}

    echo 
    echo 'Prod Onboarding Server Credentials Script Complete'
}   