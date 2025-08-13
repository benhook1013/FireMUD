# 🔄 Playtesting & Feedback

Early adopters can try new features in short-lived environments created by the
[`preview.yml`](../../.github/workflows/preview.yml) workflow. Pull requests
spin up the same Docker Compose stack used for local development so the service
layout mirrors production on a smaller scale. The stack is automatically torn
down once the workflow completes, making each preview environment ephemeral. The
workflow posts a summary comment on the pull request with a link to the preview.
See [CI/CD Pipeline](../architecture/system-architecture-cicd.md#pr-preview-environments)
for details. A dedicated staging cluster for broader playtests is available; see
[Deployment Environments](../architecture/infrastructure/deployment-environments.md#🎮-staging-environment-for-playtesting).

1. **Invite testers** from the community via Discord and email using the staging cluster.
2. **Collect feedback** through a shared form linked in the web client and store the results in the [Logging & Admin Service](../architecture/microservices/logging-admin-service/README.md).
3. **Review logs and metrics** in Grafana and Kibana to detect crashes or errors. See [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Analytics Dashboards](../architecture/microservices/logging-admin-service/analytics-dashboards.md).
4. **Iterate on the UI** based on usability issues reported by testers.
5. **Nightly resets** clear test data from the staging cluster to keep environments clean.

Feedback informs our UI/UX roadmap and upcoming releases.
