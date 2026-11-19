#!/usr/bin/env bash
# Pre-v1 Compose entrypoint for Account (ADR 0185 gate waiver). Runs as root only long enough to
# install the stand-in protected bindings with the ownership Account's protected readers require,
# then drops to the firemud user for the ordinary service start. Owed before v1: real protected
# mounts delivered by the cluster.
set -euo pipefail

fixtures=/pre-v1-fixtures
install -d -o root -g root -m 0555 /etc/firemud /etc/firemud/account-jwt-api \
  /etc/firemud/account-jwt-materializer /etc/firemud/account-jwt-readiness
install -o root -g root -m 0444 "$fixtures/account-jwt-api/binding.json" \
  "$fixtures/account-jwt-api/serving-ca.pem" "$fixtures/account-jwt-api/validator-inventory.json" \
  /etc/firemud/account-jwt-api/
install -o root -g root -m 0444 "$fixtures/materializer-binding/binding.properties" \
  /etc/firemud/account-jwt-materializer/binding.properties
install -o root -g root -m 0444 "$fixtures/readiness-binding/binding.properties" \
  /etc/firemud/account-jwt-readiness/binding.properties

install -d -o root -g root -m 0755 /var/run/secrets/firemud
install -d -o root -g firemud -m 0750 /var/run/secrets/firemud/account-jwt-api-token
install -o root -g firemud -m 0440 "$fixtures/api-token/token" \
  /var/run/secrets/firemud/account-jwt-api-token/token

for keyring in request-digest response-envelope; do
  install -d -o root -g firemud -m 0750 "/var/run/secrets/firemud/$keyring"
  install -o root -g firemud -m 0440 "$fixtures/keyrings/$keyring/keyring" \
    "/var/run/secrets/firemud/$keyring/keyring"
done

exec setpriv --reuid=firemud --regid=firemud --init-groups /usr/local/bin/start-service.sh "$@"
