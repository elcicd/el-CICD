#!/bin/bash
#!/bin/bash
set -e

cd ${EL_CICD_KUSTOMIZE_DIR}
cat <&0 > ${EL_CICD_BASE_KUSTOMIZE_DIR}/${EL_CICD_PRE_KUST_HELM_FILE}

PROFILES=${1}
mkdir -p ${EL_CICD_BASE_KUSTOMIZE_DIR} ${PROFILES}
for PROFILE in ${EL_CICD_BASE_KUSTOMIZE_DIR} ${PROFILES}
do
    cd \${PROFILE}
    
    set +e
    kustomize create --autodetect ${PROFILE} 2>/dev/null
    set -e
    
    if [[ ! -z ${LAST_PROFILE} ]]
    then
        kustomize edit add resource "../${LAST_PROFILE}"
    else
        kustomize edit add resource ${EL_CICD_PRE_KUST_HELM_FILE}
    fi

    LAST_PROFILE=${PROFILE}
    cd ..
done

kustomize build ${EL_CICD_KUSTOMIZE_DIR} > ${EL_CICD_POST_KUST_HELM_FILE}

cat ${EL_CICD_POST_KUST_HELM_FILE}

set +e