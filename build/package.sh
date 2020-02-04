#!/usr/bin/env bash
set -euo pipefail

clojure -Spom

mkdir -p target

clojure -A:depstar -m hf.depstar.uberjar target/tekton-watcher.jar
