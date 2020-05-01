#!/usr/bin/env bash

################################################################################
### Containerize tekton-watcher using vessel: https://github.com/nubank/vessel.
################################################################################

set -euo pipefail
cur_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" > /dev/null && pwd )"

tag=$1

target=${cur_dir}/../target

rm -rf $target; mkdir -p $target

## Generate the manifest of the image to be built
vessel image --registry docker.io \
       --repository alangh/tekton-watcher \
       --tag $tag \
       --base-image $cur_dir/openjdk-11.json \
       --output ${target}/tekton-watcher.json

## Containerize
vessel containerize \
       --app-root /opt/tekton-watcher \
       --classpath `clojure -Spath` \
       --main-class tekton-watcher.service \
       --manifest ${target}/tekton-watcher.json \
       --output ${target}/tekton-watcher.tar \
       --source-path src \
       --resource-path resources \
       --extra-path build/tekton-watcher.sh:/usr/local/bin/tekton-watcher \
       --preserve-file-permissions
