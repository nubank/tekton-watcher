#!/usr/bin/env bash

################################################################################
### Release a new version of tekton-watcher.
###
### This script joins all manifest files into a single one and creates a
### corresponding Github release.
################################################################################

set -euo pipefail
cur_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" > /dev/null && pwd )"

version=$1

join_manifests() {
    local readonly file=/tmp/tekton-watcher-${version}.yaml
    local readonly year=$(date +'%Y')
    cat > $file<<EOF
# Copyright ${year} Nubank
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
EOF

    for manifest in $(ls -d $cur_dir/../app/*); do
        cat $manifest >> $file
        echo '---' >> $file
    done
    mkdir -p ${cur_dir}/../target
    mv $file ${cur_dir}/../target/
}

create_release() {
    hub release create $version \
        --message "tekton-watcher $version" \
        --attach ${cur_dir}/../target/tekton-watcher-${version}.yaml
}

dirty=$(git status --porcelain)
if [ ! -z "$dirty" ]; then
    >&2 echo "Error: your working tree is dirty. Aborting release."
    exit 1
fi
echo "Releasing tekton-watcher version ${version}"
join_manifests
create_release
