# 🔄 Playtesting & Feedback Plan

Early adopters can try new features in a short-lived staging environment
created by the [`preview.yml`](../../.github/workflows/preview.yml) workflow.
This environment is intended to mirror production but with smaller node sizes.
(TODO: Not yet implemented)

1. **Invite testers** from the community via Discord and email. (TODO: Not yet implemented)
2. **Collect feedback** using a shared form linked in the web client. (TODO: Not yet implemented)
3. **Review logs and metrics** in Grafana and Kibana to detect crashes or errors. See [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Analytics Dashboards](../architecture/microservices/logging-admin-service/analytics-dashboards.md). (TODO: Not yet implemented)
4. **Iterate on the UI** based on usability issues reported by testers. (TODO: Not yet implemented)

The staging cluster resets nightly so broken worlds or accounts do not persist. (TODO: Not yet implemented)
Feedback informs our UI/UX roadmap and upcoming releases.
