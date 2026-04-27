#!/usr/bin/env bash
set -euxo pipefail

# Refresh a Codex cloud-style Linux environment for FireMUD repository work.

export DEBIAN_FRONTEND=noninteractive

# --- Refresh current repo dependencies ---
apt-get update
apt-get install -y --only-upgrade \
    ca-certificates \
    curl \
    docker.io \
    docker-compose-plugin \
    gh \
    git \
    nodejs \
    npm \
    openjdk-21-jdk-headless \
    postgresql-client \
    python3 \
    python3-pip \
    redis-tools

# --- Python tooling ---
python3 -m pip install --upgrade pip pre-commit

# --- Node workspaces used by CI/docs/security flows ---
npm --prefix config/openapi ci
npm --prefix web-client ci

# Optional follow-up maintenance steps:
#   ./gradlew check
#   ./gradlew linkCheck lintMarkdown
#   dev-tools/verify-restart-state.sh

# Sanity checks
docker --version
docker compose version
psql --version
python3 --version
redis-cli --version
gh --version
java -version
npm --version
