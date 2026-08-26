# Kibana Dashboards

This directory stores Kibana saved objects for exploring FireMUD logs indexed in Elasticsearch.

These JSON exports can be imported into Kibana to quickly restore log views and dashboards that align with the project’s logging conventions.

## Dashboards and Saved Objects

- [log-volume.json](./log-volume.json) – Saved search and visualization set focused on log volume, error spikes, and severity distribution across services.
- [player-incident-drilldown.json](./player-incident-drilldown.json) – Target-state platform-operator-only saved search targeting player-visible incidents. Its base query contains the required `environment:"__REQUIRED_ENVIRONMENT__"` sentinel before `service` and `traceId`; an unmodified import intentionally returns no records.
- [tick-region-logs.json](./tick-region-logs.json) – Saved search focused on tick and region incidents; filters on `tenantId`, `regionId`, `tickId`, and tick/Redis-related services for use during coordination and tick incident runbooks.

To use these objects, replace `__REQUIRED_ENVIRONMENT__` in `player-incident-drilldown.json` with the exact environment identifier, configure the `firemud-logs-*` index pattern (or a narrower environment index), and import through Kibana’s “Saved Objects” management screen. The importing role must be read-only and scoped to that environment’s index and fields; tenant-scoped operators additionally require mandatory tenant context and tenant-safe index/access controls before this platform-only object is exposed. Do not import the sentinel unchanged and do not broaden the index or role to cross-environment data.

## Conventions (Contract)

- Logs used by these saved objects assume structured fields such as `service`, `tenantId`, `regionId`, `traceId`, and (when applicable) `characterId`, as defined in `design/architecture/system-architecture-logging-monitoring.md`.
- Saved-object filters should treat `service` as the runtime emitter identity. Use `component`/role fields for infra semantics; do not assume alert-only identities (for example `redis-coordination`) appear as log `service` values.
- When adding new saved searches for operational runbooks, prefer filters on bounded identifiers (`tenantId`, `regionId`, `traceId`) and avoid relying on free-form message parsing.
- `player-incident-drilldown.json` is target-state and platform-operator-only. Its base query intentionally does not require `tenantId` because pre-gameplay records may omit it; it must not be exposed to tenant-scoped operators unless the environment sentinel has been replaced, tenant-safe surrounding index/access controls are enforced, and tenant context is required.
