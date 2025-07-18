# 🔄 Playtesting & Feedback Plan

Early adopters can try new features in short-lived environments created by the
[`preview.yml`](../../.github/workflows/preview.yml) workflow. Pull requests
spin up a Docker Compose stack that mirrors production services on a smaller
scale and is automatically torn down at the end of the job. A dedicated staging
cluster for broader playtests is planned; see
[Deployment Environments](../architecture/infrastructure/deployment-environments.md#🎮-staging-environment-for-playtesting).
(TODO: Not yet implemented)

1. **Invite testers** from the community via Discord and email once the staging cluster is available. (TODO: Not yet implemented)
2. **Collect feedback** using a shared form linked in the web client and store the results in the Logging & Admin Service. (TODO: Not yet implemented)
3. **Review logs and metrics** in Grafana and Kibana to detect crashes or errors. See [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Analytics Dashboards](../architecture/microservices/logging-admin-service/analytics-dashboards.md). (TODO: Not yet implemented)
4. **Iterate on the UI** based on usability issues reported by testers. (TODO: Not yet implemented)
5. **Nightly resets** will clear test data from the staging cluster to keep environments clean. (TODO: Not yet implemented)

Feedback informs our UI/UX roadmap and upcoming releases.
