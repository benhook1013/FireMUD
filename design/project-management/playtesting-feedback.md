# Playtesting & Feedback

FireMUD uses two distinct validation surfaces:

- **Engineering preview environments** – Pull requests may deploy hosted per-PR environments through [`preview.yml`](../../.github/workflows/preview.yml) for reviewer and developer validation. These previews persist for the lifetime of the PR, but they remain engineering tools rather than the canonical creator-facing playtest experience.
- **Creator playtest realms** – The canonical product playtest flow uses creator-managed forked realms derived from source realm snapshots under the v1 fork-snapshot boundary in `system-architecture-versioning-runtime.md`. These realms are temporary, isolated from production writes, use the same platform accounts with explicit access grants, and are surfaced to authorized testers through the same authenticated lobby/realm-selection contract used for normal gameplay.

The creator-facing playtest loop is:

1. **Create a forked playtest realm** from the current production realm or another source realm snapshot.
2. **Target the build under evaluation** by launching the fork on the desired `versionId` and optional `scriptPatchVersion`.
3. **Invite testers** from the community via Discord, email, or direct tenant access grants. Only authorized testers see the fork in realm discovery.
4. **Collect feedback** through a shared form linked in the web client and store the results in the [Logging & Admin Service](../architecture/microservices/logging-admin-service/README.md), tagged to the playtest realm.
5. **Review logs and metrics** in Grafana and Kibana to detect crashes or errors. See [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Analytics Dashboards](../architecture/microservices/logging-admin-service/analytics-dashboards.md).
6. **Reset or expire the fork** when a test cycle is complete. Fork data can be discarded or recreated from a new snapshot; runtime state does not merge back into production.
7. **Promote by normal rollout** only after the team is satisfied. Production updates still use the normal launch/cutover path rather than "converting" the fork into production.

V1 fork snapshot examples:

| State / Artifact | Fork Behavior |
| --- | --- |
| Character progression, inventory, learned abilities | Copied into fork-local gameplay state |
| Published/runtime version pointers | Copied from source or explicitly set to target build under test |
| Billing records, invoices, payment methods | Excluded |
| Live auth sessions and connect-token replay state | Excluded |
| Source moderation cases and operator audit trails | Not cloned as active source records |
| Fork-generated analytics and moderation events | Created separately and tagged to the fork realm |

Feedback informs our UI/UX roadmap and upcoming releases.
