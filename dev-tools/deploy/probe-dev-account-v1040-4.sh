#!/usr/bin/env bash
set -euo pipefail

# Run only inside the trusted dev database probe, while the existing deployment
# holds writer quiescence. The caller verifies the live Namespace UID, deployed
# and target revisions, and the expected Account history prefix at that base.
if [[ $# -ne 5 ]]; then
  echo "usage: $0 <namespace> <namespace-uid> <deployed-sha> <target-sha> <applied-origin-migrations:0..3>" >&2
  exit 2
fi

namespace="$1"
namespace_uid="$2"
deployed_sha="$3"
target_sha="$4"
applied_origin_migrations="$5"
if [[ "$namespace" != dev || ! "$namespace_uid" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$ ||
  ! "$deployed_sha" =~ ^[a-f0-9]{40}$ || ! "$target_sha" =~ ^[a-f0-9]{40}$ ||
  "$deployed_sha" == "$target_sha" || ! "$applied_origin_migrations" =~ ^[0-3]$ ]]; then
  echo "Account V1040.4 probe requires a valid dev Namespace and exact retained-to-target comparison." >&2
  exit 2
fi
if [[ -z "${FIREMUD_POSTGRES_USER:-}" ]]; then
  echo "Account V1040.4 probe requires the existing hosted PostgreSQL connection identity." >&2
  exit 2
fi

# The caller supplies the existing in-Pod PostgreSQL environment. Direct
# arguments fix the destination and -X excludes local psql startup commands.
unset PGHOSTADDR PGSERVICE PGSERVICEFILE
if ! result="$(
  PGCONNECT_TIMEOUT=5 PGOPTIONS='' PGPASSFILE=/dev/null psql -XqAtw \
    --host=postgres.dev.svc.cluster.local --port=5432 --dbname=firemud \
    --username="$FIREMUD_POSTGRES_USER" \
    --set=ON_ERROR_STOP=1 --set="expected_prefix=$applied_origin_migrations" \
    2>/dev/null <<'SQL'
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = pg_catalog;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';

SELECT current_database() = 'firemud'
  AND EXISTS (
    SELECT 1 FROM pg_class c
    WHERE c.oid = to_regclass('account_service.flyway_schema_history_account_service')
      AND c.relkind = 'r' AND NOT c.relrowsecurity
      AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhparent = c.oid)
  )
  AND EXISTS (
    SELECT 1 FROM pg_class c
    WHERE c.oid = to_regclass('account_service.account_tenant_capacity_reservations')
      AND c.relkind = 'r'
  )
  AND to_regclass('account_service.account_capacity_origin_attempt_unique') IS NULL
  AS expected_schema
\gset
\if :expected_schema
  -- Flyway 13.13.0 SQL checksums for the immutable migration files.
  WITH expected(version, script, prefix, checksum) AS (
    VALUES
      ('1040', 'V1040__account_tenant_capacity_owner.sql', 0, 1080396723),
      ('1040.1', 'V1040.1__account_capacity_origin_decisions.sql', 1, 2014106789),
      ('1040.2', 'V1040.2__bound_capacity_origin_refusals.sql', 2, -1834870498),
      ('1040.3', 'V1040.3__validate_only_inserted_refusal_budgets.sql', 3, 1807395108)
  )
  SELECT count(*) = :expected_prefix + 1
    AND count(DISTINCT h.version) = :expected_prefix + 1
    AND COALESCE(bool_and(e.prefix IS NOT NULL AND e.prefix <= :expected_prefix
      AND h.success IS TRUE AND h.script IS NOT DISTINCT FROM e.script
      AND h.checksum IS NOT DISTINCT FROM e.checksum), false)
    AS expected_history
  FROM account_service.flyway_schema_history_account_service h
  LEFT JOIN expected e ON e.version = h.version
  WHERE h.version ~ '^1040([.]|$)'
  \gset
  \if :expected_history
    SELECT :expected_prefix = 0 AS first_origin_migration,
      to_regclass('account_service.account_capacity_origin_decisions') IS NULL AS origin_missing,
      EXISTS (
        SELECT 1 FROM pg_class c
        JOIN pg_attribute a ON a.attrelid = c.oid
        WHERE c.oid = to_regclass('account_service.account_capacity_origin_decisions')
          AND c.relkind = 'r' AND NOT c.relrowsecurity
          AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhparent = c.oid)
          AND a.attname = 'admission_attempt_id' AND a.atttypid = 'pg_catalog.uuid'::regtype
          AND a.attnotnull AND NOT a.attisdropped
      ) AS expected_origin_relation
    \gset
    \if :first_origin_migration
      \if :origin_missing
        \echo first-origin-migration
      \else
        \echo unverified-origin-relation
      \endif
    \elif :expected_origin_relation
      SELECT EXISTS (
        SELECT 1
        FROM account_service.account_capacity_origin_decisions
        GROUP BY admission_attempt_id
        HAVING count(*) > 1
      ) AS has_duplicates
      \gset
      \if :has_duplicates
        \echo duplicates-present
      \else
        \echo retained-no-duplicates
      \endif
    \else
      \echo unverified-origin-relation
    \endif
  \else
    \echo unverified-account-history
  \endif
\else
  \echo unverified-account-schema
\endif
ROLLBACK;
SQL
)"; then
  echo "Account V1040.4 probe could not complete the read-only PostgreSQL diagnostic." >&2
  exit 1
fi

case "$result" in
  first-origin-migration|retained-no-duplicates)
    if [[ "$result" == first-origin-migration && "$applied_origin_migrations" != 0 ]] ||
      [[ "$result" == retained-no-duplicates && "$applied_origin_migrations" == 0 ]]; then
      echo "Account V1040.4 probe received an inconsistent diagnostic result." >&2
      exit 1
    fi
    printf 'account-v1040-4-probe=%s namespace=%s namespace_uid=%s deployed_sha=%s target_sha=%s schema=account_service applied_origin_migrations=%s checksum_comparison=verified-v1040-prefix\n' \
      "$result" "$namespace" "$namespace_uid" "$deployed_sha" "$target_sha" "$applied_origin_migrations"
    ;;
  duplicates-present)
    echo "Account V1040.4 probe found duplicate retained admission attempts. Account-owned disposition is required." >&2
    exit 1
    ;;
  unverified-account-schema|unverified-account-history|unverified-origin-relation)
    printf 'Account V1040.4 probe refused an unverified database state (%s).\n' "$result" >&2
    exit 1
    ;;
  *)
    echo "Account V1040.4 probe received an unexpected diagnostic result." >&2
    exit 1
    ;;
esac
