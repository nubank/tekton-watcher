#!/usr/bin/env bash

################################################################################
### Run all tests using Cognitect test-runner.
################################################################################

set -euo pipefail

clojure -Adev -m cognitect.test-runner $@
