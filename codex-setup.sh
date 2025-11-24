#!/usr/bin/env bash
set -euxo pipefail

# --- System dependencies needed by the repo ---
apt-get update
apt-get install -y --no-install-recommends \
    docker.io \
    docker-compose \
    postgresql-client \
    redis-tools \
    chromium-browser

# --- Python tooling ---
pip install --upgrade pip pre-commit

# --- Node workspaces (needed before the Gradle build) ---
npm --prefix config/openapi ci
npm --prefix web-client ci

# --- Java/Gradle bootstrap ---
# ./gradlew build

# Optional sanity checks (fail early if a tool is missing)
docker --version
psql --version
redis-cli --version
npm --version

