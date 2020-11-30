#!/usr/bin/bash

CUSTOM_CONFIG_SCRIPTS=$(find "$1" -type f -executable \( -name "$2-*.sh" -o -name 'all-*.sh' \) | sort | tr '\n' ' ')
if [[ ! -z ${CUSTOM_CONFIG_SCRIPTS} ]]
then
    for FILE in ${CUSTOM_CONFIG_SCRIPTS}
    do
        echo
        echo "Running custom script $(basename ${FILE})" 
        eval "${FILE}"
    done
else
    echo
    echo 'No custom config scripts found...'
fi