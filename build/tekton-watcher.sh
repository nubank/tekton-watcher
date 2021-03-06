#!/usr/bin/env sh

################################################################################
### This script executes the tekton-watcher with the correct classpath.
################################################################################

set -eu

# Doc: Constructs a classpath specification as expected by java command from the
# files generated by Vessel.
# usage: classpath
classpath() {
    local classes_and_libs_dir=/opt/tekton-watcher/WEB-INF
    echo $classes_and_libs_dir/classes:$(join_by ':' $(ls -d $classes_and_libs_dir/lib/*))
}

# Doc: join elements of the supplied array separating them by the separator.
# Usage: join_by <separator> <array>
# Arguments: $1: separator
# $2: array
join_by() {
    local IFS="$1"; shift; echo "$*";
}

exec java \
     -Dclojure.spec.skip-macros=true \
     -cp "$(classpath)" \
     tekton_watcher.service
