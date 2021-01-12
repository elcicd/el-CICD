# create and add sealed secret for read only el-CICD access to project
echo
echo 'Creating the el-cicd git repository read-only ssh key secret'
rm -rf ${SECRET_FILE_TEMP_DIR}
mkdir -p ${SECRET_FILE_TEMP_DIR}

echo
echo 'Creating the image repository pull secrets for prod environment'
SEALED_SECRET_FILE=${SECRET_FILE_TEMP_DIR}/sealed-secret.yml
CICD_ENVIRONMENTS="$(echo $TEST_ENVS | sed 's/.*://') ${PROD_ENV}"
for ENV in ${CICD_ENVIRONMENTS}
do
    echo
    echo "Creating ${ENV} image pull secret"
    U_NAME=$(eval echo \${${ENV}_IMAGE_REPO_USERNAME})
    SEC_NAME=$(eval echo \${${ENV}_IMAGE_REPO_PULL_SECRET})
    TKN_FILE=$(eval echo \${${ENV}_PULL_TOKEN_FILE})
    DOMAIN=$(eval echo \${${ENV}_IMAGE_REPO_DOMAIN})

    DRY_RUN=client
    if [[ ${OCP_VERSION} == 3 ]]
    then
        DRY_RUN=true
    fi

    SECRET_FILE_IN=${SECRET_FILE_TEMP_DIR}/${SEC_NAME}
    oc create secret docker-registry ${SEC_NAME}  --docker-username=${U_NAME} --docker-password=$(cat ${TKN_FILE})  --docker-server=${DOMAIN} \
        --dry-run=${DRY_RUN} -o yaml  > ${SECRET_FILE_IN}

    kubeseal --scope cluster-wide <${SECRET_FILE_IN} >${SEALED_SECRET_FILE}
    oc apply -f ${SEALED_SECRET_FILE} -n ${EL_CICD_PROD_MASTER_NAMEPACE}

    LABEL_NAME=$(echo ${ENV} | tr '[:upper:]' '[:lower:]')-env
    oc label -f ${SEALED_SECRET_FILE} -n ${EL_CICD_PROD_MASTER_NAMEPACE} --overwrite ${LABEL_NAME}=true
done

rm -f ${SECRET_FILE_TEMP_DIR}/*

JENKINS_URL=$(oc get route jenkins -o jsonpath='{.spec.host}')
export JENKINS_CREATE_CREDS_URL="https://${JENKINS_URL}/credentials/store/system/domain/_/createCredentials"
export BEARER_TOKEN=$(oc whoami -t)

# NOTE: THIS DEFAULT CREDS GIVE READ-ONLY ACCESS TO THE el-cicd REPOSITORY; MODIFY el-cicdGithubJenkinsSshCredentials.xml TO USE YOUR OWN FORK
echo
echo 'Pushing el-CICD git read only private key to Jenkins'
export SECRET_FILE_NAME=${SECRET_FILE_TEMP_DIR}/secret.xml
cat ./resources/templates/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${EL_CICD_READ_ONLY_GITHUB_PRIVATE_KEY_ID}/g" > ${SECRET_FILE_NAME}
cat ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} >> ${SECRET_FILE_NAME}
cat ./resources/templates/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE_NAME}
curl -k -X POST -H "Authorization: Bearer ${BEARER_TOKEN}" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${JENKINS_CREATE_CREDS_URL}
rm -f ${SECRET_FILE_NAME}

echo
echo 'Pushing el-CICD-config git read only private key to Jenkins'
cat ./resources/templates/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID}/g" > ${SECRET_FILE_NAME}
cat ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} >> ${SECRET_FILE_NAME}
cat ./resources/templates/jenkinsSshCredentials-postfix.xml >> ${SECRET_FILE_NAME}
curl -k -X POST -H "Authorization: Bearer ${BEARER_TOKEN}" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${JENKINS_CREATE_CREDS_URL}
rm -f ${SECRET_FILE_NAME}

echo 'Pushing git repo access token to Jenkins'
export SECRET_TOKEN=$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE})
cat ./resources/templates/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${GIT_SITE_WIDE_ACCESS_TOKEN_ID}/; s/%TOKEN%/${SECRET_TOKEN}/" > ${SECRET_FILE_NAME}
curl -k -X POST -H "Authorization: Bearer ${BEARER_TOKEN}" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${JENKINS_CREATE_CREDS_URL}
rm -f ${SECRET_FILE_NAME}

for ENV in ${CICD_ENVIRONMENTS}
do
    ACCESS_TOKEN_ID=$(eval echo \${${ENV}_IMAGE_REPO_ACCESS_TOKEN_ID})
    SECRET_TOKEN_FILE=$(eval echo \${${ENV}_PULL_TOKEN_FILE})

    echo
    echo "Pushing ${ENV} image repo access tokens per environment to Jenkins"
    export SECRET_TOKEN=$(cat ${SECRET_TOKEN_FILE})
    # NOTE: using '|' (pipe) as a delimeter in sed TOKEN replacement, since '/' is a legitimate token character
    cat ./resources/templates/jenkinsTokenCredentials-template.xml | sed "s/%ID%/${ACCESS_TOKEN_ID}/; s|%TOKEN%|${SECRET_TOKEN}|" > ${SECRET_FILE_NAME}
    curl -ksS -X POST -H "Authorization: Bearer ${BEARER_TOKEN}" -H "content-type:application/xml" --data-binary @${SECRET_FILE_NAME} ${JENKINS_CREATE_CREDS_URL}
    rm -f ${SECRET_FILE_NAME}
done
rm -rf ${SECRET_FILE_TEMP_DIR}