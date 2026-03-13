# Playtesting & Feedback

FireMUD uses two distinct validation surfaces:

- **Engineering preview environments** – Pull requests may still create short-lived environments through [`preview.yml`](../../.github/workflows/preview.yml) for developer validation. These are engineering tools, not the canonical creator-facing playtest experience.
- **Creator playtest realms** – The canonical product playtest flow uses creator-managed forked realms derived from source realm snapshots. These realms are temporary, isolated from production writes, and surfaced to authorized testers through the same authenticated lobby/realm-selection contract used for normal gameplay.

The creator-facing playtest loop is:

1. **Create a forked playtest realm** from the current production realm or another source realm snapshot.
2. **Target the build under evaluation** by launching the fork on the desired `versionId` and optional `scriptPatchVersion`.
3. **Invite testers** from the community via Discord, email, or direct tenant access grants. Only authorized testers see the fork in realm discovery.
4. **Collect feedback** through a shared form linked in the web client and store the results in the [Logging & Admin Service](../architecture/microservices/logging-admin-service/README.md), tagged to the playtest realm.
5. **Review logs and metrics** in Grafana and Kibana to detect crashes or errors. See [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Analytics Dashboards](../architecture/microservices/logging-admin-service/analytics-dashboards.md).
6. **Reset or expire the fork** when a test cycle is complete. Fork data can be discarded or recreated from a new snapshot; runtime state does not merge back into production.
7. **Promote by normal rollout** only after the team is satisfied. Production updates still use the normal launch/cutover path rather than "converting" the fork into production.

Feedback informs our UI/UX roadmap and upcoming releases.
