# Kibana Dashboards

This directory stores Kibana saved objects for exploring FireMUD logs indexed in Elasticsearch.

These JSON exports can be imported into Kibana to quickly restore log views and dashboards that align with the project’s logging conventions.

## Dashboards and Saved Objects

- [log-volume.json](./log-volume.json) – Saved search and visualization set focused on log volume, error spikes, and severity distribution across services.
- [player-incident-drilldown.json](./player-incident-drilldown.json) – Target-state platform-operator-only saved search targeting player-visible incidents. Its index reference contains the required `firemud-logs-env-__REQUIRED_ENVIRONMENT__-*` sentinel and its query is bounded by `service` and `traceId`; an unmodified import intentionally matches no index.
- [tick-region-logs.json](./tick-region-logs.json) – Saved search focused on tick and region incidents; filters on `tenantId`, `regionId`, `tickId`, and tick/Redis-related services for use during coordination and tick incident runbooks.

To use this object, replace `__REQUIRED_ENVIRONMENT__` in its index reference with the exact environment value (for example, `staging`, yielding `firemud-logs-env-staging-*`), configure the corresponding read-only index and access boundary, and import through Kibana’s “Saved Objects” management screen. The repository currently has no emitted `environment` log field, ingest enrichment, or deployed environment-scoped index/role mapping proving that boundary; this target-state object is therefore not runtime queryability proof until the deployment supplies and verifies it. Tenant-scoped operators additionally require mandatory tenant context and tenant-safe index/access controls before this platform-only object is exposed. Do not import the sentinel unchanged and do not broaden the index or role to `firemud-logs-*` or cross-environment data.

## Conventions (Contract)

- Logs used by these saved objects assume structured fields such as `service`, `tenantId`, `regionId`, `traceId`, and (when applicable) `characterId`, as defined in `design/architecture/system-architecture-logging-monitoring.md`; environment isolation is supplied by the selected index and access boundary, not an uncontracted log field.
- Saved-object filters should treat `service` as the runtime emitter identity. Use `component`/role fields for infra semantics; do not assume alert-only identities (for example `redis-coordination`) appear as log `service` values.
- When adding new saved searches for operational runbooks, prefer filters on bounded identifiers (`tenantId`, `regionId`, `traceId`) and avoid relying on free-form message parsing.
- `player-incident-drilldown.json` is target-state and platform-operator-only. Its base query intentionally does not require `tenantId` because pre-gameplay records may omit it; it must not be exposed to tenant-scoped operators unless the environment index sentinel has been replaced, tenant-safe surrounding index/access controls are enforced, and tenant context is required.
