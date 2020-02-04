#!/usr/bin/env bash
set -euo pipefail

kubectl proxy --port 8080 &

java -cp ${TW_HOME}/tekton-watcher.jar clojure.main -m tekton-watcher.service
