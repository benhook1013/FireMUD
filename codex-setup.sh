#!/usr/bin/env bash
set -euxo pipefail

# Bootstrap a Codex cloud-style Linux environment for FireMUD repository work.
# This script is a convenience helper, not the canonical source of truth; keep
# it aligned with DEVELOPER_SETUP.md and the repo's current dev-tools workflow.

export DEBIAN_FRONTEND=noninteractive

# --- System dependencies used across current repo workflows ---
apt-get update
apt-get install -y --no-install-recommends \
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

# Optional follow-up bootstrap steps, depending on what the environment needs:
#   ./gradlew check
#   ./gradlew linkCheck lintMarkdown
#   dev-tools/verify-fresh-bootstrap.sh

# Sanity checks
docker --version
docker compose version
psql --version
python3 --version
redis-cli --version
gh --version
java -version
npm --version
