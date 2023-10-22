#!/bin/bash
#!/bin/bash
set -e
PROFILES=${1}

cd "$(dirname ${0})"

mkdir -p ${elcicd_BASE_KUSTOMIZE_DIR} ${PROFILES}
cat <&0 > ${elcicd_BASE_KUSTOMIZE_DIR}/${elcicd_PRE_KUST_HELM_FILE}

for PROFILE in ${elcicd_BASE_KUSTOMIZE_DIR} ${PROFILES}
do
    cd \${PROFILE}
    
    set +e
    kustomize create --autodetect ${PROFILE} 2>/dev/null
    set -e
    
    if [[ ! -z ${LAST_PROFILE} ]]
    then
        kustomize edit add resource "../${LAST_PROFILE}"
    else
        kustomize edit add resource ${elcicd_PRE_KUST_HELM_FILE}
    fi

    LAST_PROFILE=${PROFILE}
    cd ..
done

kustomize build ${elcicd_POST_RENDERER_KUSTOMIZE_DIR} > ${elcicd_POST_KUST_HELM_FILE}

cat ${elcicd_POST_KUST_HELM_FILE}

set +e