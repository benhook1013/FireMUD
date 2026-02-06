# Kibana Dashboards

This directory stores Kibana saved objects for exploring FireMUD logs indexed in Elasticsearch.

These JSON exports can be imported into Kibana to quickly restore log views and dashboards that align with the project’s logging conventions.

## Dashboards and Saved Objects

- [log-volume.json](./log-volume.json) – Saved search and visualization set focused on log volume, error spikes, and severity distribution across services.
- [player-incident-drilldown.json](./player-incident-drilldown.json) – Saved search targeting player-visible incidents; filters on `playerId`, `tenantId`, `traceId`, and service to help investigate login, session, and gameplay issues quickly.
- [tick-region-logs.json](./tick-region-logs.json) – Saved search focused on tick and region incidents; filters on `tenantId`, `regionId`, `tickId`, and tick/Redis-related services for use during coordination and tick incident runbooks.

To use these objects, open Kibana’s “Saved Objects” management screen and import the JSON file, then point the imported visualizations at the Elasticsearch index pattern configured for FireMUD logs.

## Conventions (Contract)

- Logs used by these saved objects assume structured fields such as `service`, `tenantId`, `regionId`, `traceId`, and (when applicable) `playerId`, as defined in `design/architecture/system-architecture-logging-monitoring.md`.
- When adding new saved searches for operational runbooks, prefer filters on bounded identifiers (`tenantId`, `regionId`, `traceId`) and avoid relying on free-form message parsing.
