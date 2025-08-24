#!/usr/bin/env bash
set -euxo pipefail

# --- Refresh system packages needed by the repo ---
apt-get update
apt-get install -y --only-upgrade \
    docker.io \
    docker-compose \
    postgresql-client \
    redis-tools \
    chromium-browser

# --- Python tooling ---
pip install --upgrade pre-commit

# --- Node workspaces (needed before any Gradle tasks) ---
npm --prefix config/openapi ci
npm --prefix web-client ci

# --- Java/Gradle bootstrap ---
./gradlew build

# Optional sanity checks
docker --version
psql --version
redis-cli --version
npm --version

