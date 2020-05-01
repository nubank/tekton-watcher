#!/usr/bin/env bash

################################################################################
### Create a Kind cluster and setup the development environment to run Tekton pipelines.
################################################################################

set -euo pipefail

# Create a new Kind cluster for Tektoncd
kind create cluster --name tektoncd --wait 1m

# Ensure the correct context
kubectl config use kind-tektoncd

# Install latest version of Tekton/pipeline
kubectl apply --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
