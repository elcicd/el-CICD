#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

PROJECT_REPOSITORY=../el-CICD-project-repository
PROJECT_REPOSITORY_CONFIG=${PROJECT_REPOSITORY}/config
PROJECT_REPOSITORY_AGENTS=${PROJECT_REPOSITORY}/agents
source ${PROJECT_REPOSITORY_CONFIG}/el-cicd-bootstrap.config
source ${PROJECT_REPOSITORY_CONFIG}/el-cicd-secrets.config

EL_CICD_GIT_REPO_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_REPO_ACCESS_TOKEN_FILE})
SECRET_FILE='/tmp/sshKeyFile.json'

EL_CICD_GITHUB_URL="https://${EL_CICD_GIT_REPO_ACCESS_TOKEN}@${EL_CICD_GIT_API_DOMAIN}/repos/${EL_CICD_ORGANIZATION}"
EL_CICD_PROJECT_REPOSITORY_GITHUB_URL=${EL_CICD_GITHUB_URL}/el-CICD-project-repository/keys
EL_CICD_GITHUB_URL=${EL_CICD_GITHUB_URL}/el-CICD/keys

echo
echo "Deleting read only deploy key for el-CICD"
KEY_ID=$(curl -ksS -X GET ${EL_CICD_GITHUB_URL} | jq ".[] | select(.title  == \"${EL_CICD_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE}\") | .id")
curl -ksS -X DELETE ${EL_CICD_GITHUB_URL}/${KEY_ID}

echo
echo "Deleting read only deploy key for el-CICD-project-repository"
KEY_ID=$(curl -ksS -X GET ${EL_CICD_PROJECT_REPOSITORY_GITHUB_URL} | jq ".[] | select(.title  == \"${EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_DEPLOY_KEY_TITLE}\") | .id")
curl -ksS -X DELETE ${EL_CICD_PROJECT_REPOSITORY_GITHUB_URL}/${KEY_ID}

echo
echo "Adding read only deploy key for el-CICD"
cat ${TEMPLATES_DIR}/githubSshCredentials-prefix.json | sed "s/%DEPLOY_KEY_NAME%/${EL_CICD_SSH_READ_ONLY_PUBLIC_DEPLOY_KEY_TITLE}/" > ${SECRET_FILE}
cat ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub >> ${SECRET_FILE}
cat ${TEMPLATES_DIR}/githubSshCredentials-postfix.json >> ${SECRET_FILE}
sed -i -e 's/false/true/' ${SECRET_FILE}

curl -ksS -X POST -H Accept:application/vnd.github.v3+json -d @${SECRET_FILE} ${EL_CICD_GITHUB_URL}

echo
echo "Adding read only deploy key for el-CICD-project-repository"
cat ${TEMPLATES_DIR}/githubSshCredentials-prefix.json | sed "s/%DEPLOY_KEY_NAME%/${EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_DEPLOY_KEY_TITLE}/" > ${SECRET_FILE}
cat ${EL_CICD_PROJECT_INFO_REPOSITORY_READ_ONLY_DEPLOY_KEY_FILE}.pub >> ${SECRET_FILE}
cat ${TEMPLATES_DIR}/githubSshCredentials-postfix.json >> ${SECRET_FILE}
# sed -i -e 's/false/true/' ${SECRET_FILE}

curl -ksS -X POST -H Accept:application/vnd.github.v3+json -d @${SECRET_FILE} ${EL_CICD_PROJECT_REPOSITORY_GITHUB_URL}

rm -f ${SECRET_FILE}
