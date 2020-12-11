#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

cd "$(dirname "$0")"

echo
echo "==================================================================="
echo "WARNING:"
echo "   This script should only be run on the production bastion"
echo
echo "   WHEN USING THIS IN YOUR OWN CLUSTER:"
echo "       FORK THE el-CICD REPOSITORY FIRST AND CREATE YOUR OWN PUBLIC/KEYS AND CREDENTIALS AS NEEDED"
echo
echo "   ACCESS TO THE PROD MASTER JENKINS SHOULD BE RESTRICTED TO CLUSTER ADMINS"
echo "==================================================================="
echo

echo
echo 'Loading environment'
PROJECT_REPOSITORY=../el-CICD-project-repository
PROJECT_REPOSITORY_CONFIG=${PROJECT_REPOSITORY}/config
PROJECT_REPOSITORY_AGENTS=${PROJECT_REPOSITORY}/agents
source ${PROJECT_REPOSITORY_CONFIG}/el-cicd-bootstrap.config
source ${PROJECT_REPOSITORY_CONFIG}/el-cicd-secrets.config

if [[ -z "${EL_CICD_PROD_MASTER_NAMEPACE}" ]]
then
    echo "el-CICD prod master project must be defined in el-cicd-bootstrap.config"
    echo "Set the value of EL_CICD_PROD_MASTER_NAMEPACE el-cicd-bootstrap.config to and rerun."
    echo "Exiting."
    exit 1
fi

echo -n "Confirm the wildcard domain for the cluster: ${CLUSTER_WILDCARD_DOMAIN}? [Y/n] "
read -n 1 CONFIRM_WILDCARD
echo
if [[ ${CONFIRM_WILDCARD} != 'Y' ]]
then
    echo "CLUSTER_WILDCARD_DOMAIN needs to be properly set in el-cicd-bootstrap.config and then rerun this script"
    echo "Exiting."
    exit 1
fi

DEL_NAMESPACE=$(oc projects -q | grep ${EL_CICD_PROD_MASTER_NAMEPACE} | tr -d '[:space:]')
if [[ ! -z "${DEL_NAMESPACE}" ]]
then
    echo "Found: ${DEL_NAMESPACE}"
    echo -n "Confirm deletion of el-CICD master namespace: ${EL_CICD_PROD_MASTER_NAMEPACE}? [Y/n] "
    read -n 1 CONFIRM_DELETE
    echo
    if [[ ${CONFIRM_DELETE} != 'Y' ]]
    then
        echo "Deleting el-CICD prod master namespace must be completed to continuing."
        echo "Exiting."
        exit 1
    fi

    oc delete project ${EL_CICD_PROD_MASTER_NAMEPACE}
    until
        !(oc project ${EL_CICD_PROD_MASTER_NAMEPACE} > /dev/null 2>&1)
    do
        echo -n '.'
        sleep 1
    done
fi

# create Jenkins in new devops-management project
# It is suggested to set up some nodes in a cicd region or equivalent so as not to affect or compete with deployed applications
echo
EL_CICD_PROD_MASTER_NODE_SELECTORS=$(echo ${EL_CICD_PROD_MASTER_NODE_SELECTORS} | tr -d '[:space:]')
oc adm new-project ${EL_CICD_PROD_MASTER_NAMEPACE} --node-selector="${EL_CICD_PROD_MASTER_NODE_SELECTORS}"

oc project ${EL_CICD_PROD_MASTER_NAMEPACE}

oc create cm ${EL_CICD_META_INFO_NAME} --from-env-file=${PROJECT_REPOSITORY_CONFIG}/el-cicd-bootstrap.config

echo
echo -n "Update Jenkins to latest image? [Y/n] "
read -t 10 -n 1 CONFIRM_UPDATE_JENKINS
echo
if [[ ${CONFIRM_UPDATE_JENKINS} == 'Y' ]]
then
    oc import-image jenkins -n openshift
fi

oc new-app jenkins-persistent -p MEMORY_LIMIT=${JENKINS_MEMORY_LIMIT} \
                              -p VOLUME_CAPACITY=${JENKINS_VOLUME_CAPACITY} \
                              -p DISABLE_ADMINISTRATIVE_MONITORS=${JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                              -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                              -n ${EL_CICD_PROD_MASTER_NAMEPACE}

#-- Jenkins needs cluster-admin to run pipeline creation script
oc adm policy add-cluster-role-to-user -z jenkins cluster-admin -n ${EL_CICD_PROD_MASTER_NAMEPACE}

# install latest Sealed Secrets
INSTALL_KUBESEAL='N'
SEALED_SECRET_RELEASE=$(curl --silent "https://api.github.com/repos/bitnami-labs/sealed-secrets/releases/latest" | jq -r .tag_name)
SS_CONTROLLER_EXISTS=$(oc get Deployment sealed-secrets-controller -n kube-system)

if [[ -f /usr/local/bin/kubeseal &&  ! -z "${SS_CONTROLLER_EXISTS}" ]]
then
    OLD_VERSION=$(kubeseal --version)
    echo
    echo "Do you wish to reinstall/upgrade sealed-secrets, kubeseal and controller?"
    echo -n "${OLD_VERSION} to ${SEALED_SECRET_RELEASE}? [Y/n] "
    read -t 10 -n 1 INSTALL_KUBESEAL
    echo
else
    echo "Sealed Secrets not found..."
    INSTALL_KUBESEAL='Y'
fi

if [[ ${INSTALL_KUBESEAL} == 'Y' ]]
then
    echo
    sudo rm -f /usr/local/bin/kubeseal /tmp/kubseal
    wget https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRET_RELEASE}/kubeseal-linux-amd64 -O /tmp/kubeseal
    sudo install -m 755 /tmp/kubeseal /usr/local/bin/kubeseal
    sudo rm -f /tmp/kubseal

    echo "kubeseal version ${SEALED_SECRET_RELEASE} installed"

    oc apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/${SEALED_SECRET_RELEASE}/controller.yaml

    echo "Create custom cluster role for the management of sealedsecrets by Jenkins service accounts"
    oc apply -f ./resources/templates/sealed-secrets-management.yml

    echo "Sealed Secrets Controller Version ${SEALED_SECRET_RELEASE} installed!"
fi

echo
echo 'Creating the prod project onboarding pipeline'
oc process -f ./resources/buildconfigs/prod-project-onboarding-pipeline-template.yml \
    -p EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
  | oc create -f - -n ${EL_CICD_PROD_MASTER_NAMEPACE}

# wait for jenkins container to start
echo
echo -n "Waiting for Jenkins to be ready."
sleep 3
until
    sleep 3 && oc get pods -l name=jenkins | grep "1/1"
do
    echo -n '.'
done

echo 'Jenkins up, sleep for 10 more seconds to make sure server REST api is ready'
sleep 10
echo

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
echo 'Pushing el-CICD-project-info-repository git read only private key to Jenkins'
cat ./resources/templates/jenkinsSshCredentials-prefix.xml | sed "s/%UNIQUE_ID%/${EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID}/g" > ${SECRET_FILE_NAME}
cat ${EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_DEPLOY_KEY_FILE} >> ${SECRET_FILE_NAME}
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

HAS_BASE_AGENT=$(oc get --ignore-not-found is jenkins-agent-el-cicd-base -n openshift -o jsonpath='{.metadata.name}')
if [[ ! -z ${HAS_BASE_AGENT} ]]
then
    echo
    echo -n "Update Jenkins Base Agent image? [Y/n] "
    read -t 10 -n 1 CONFIRM_UPDATE_JENKINS_BASE_AGENT
    echo
else
    echo "Jenkins Base Agent image not found..."
    CONFIRM_UPDATE_JENKINS_BASE_AGENT='Y'
fi

if [[ ${CONFIRM_UPDATE_JENKINS_BASE_AGENT} == 'Y' ]]
then
    echo
    echo "Creating Jenkins Base Agent"
    oc delete --ignore-not-found bc jenkins-agent-el-cicd-base -n openshift
    sleep 5
    cat ${PROJECT_REPOSITORY_AGENTS}/Dockerfile.base | oc new-build -D - --name jenkins-agent-el-cicd-base -n openshift
    sleep 10

    oc logs -f jenkins-agent-el-cicd-base-1-build  -n openshift
fi

./el-cicd-run-custom-config-scripts.sh ${PROJECT_REPOSITORY_CONFIG} prod

echo 
echo 'Prod Onboarding Server Bootstrap Script Complete'