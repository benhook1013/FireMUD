#!/usr/bin/env bash
set -euo pipefail

# 2025-11-29 focused cloc run over the selected folders reported ~1,997 files and 197,595 lines of code.
# Directories that contain documentation, source, scripts, and configuration we care about.
dirs=(
  .github
  buildSrc
  charts
  config
  design
  dev-tools
  docker
  gradle
  k8s
  protos
  services
  web-client
)

exclude="--exclude-dir=.git,node_modules,target,bower_components"

echo "Running cloc on selected top-level folders (excluding generated/dependency trees)..."
cloc "${dirs[@]}" $exclude
