#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

read -r -d '' HELP_MSG << EOM
Usage: bootstrap-dev.sh [OPTION] [root-config-file]

el-CICD Admin Utility

Options:
    -D,   --setup-dev:       setup an environment for developing el-CICD
    -T,   --tear-down-dev:   tear down an environment for developing el-CICD
          --help:            display this help text and exit

root-config-file:
    file name or path to a root configuration file relative the root of the el-CICD-config directory
EOM

echo
case ${1} in

    '--setup-dev' | '-D')
        $(pwd)/kubectl-el_cicd_adm -D ${2}
    ;;

    '--tear-down-dev' | '-T')
        $(pwd)/kubectl-el_cicd_adm -T ${2}
    ;;

    *)
        echo "ERROR: Unknown command option '${CLI_OPTION}'"
        echo
        echo "${HELP_MSG}"
        exit 1
    ;;
esac

