# Logging & Admin Service Task List

- **Prepare Helm chart for Logging & Admin Service**
- **Develop Logging & Admin Service**
  - Collect logs from all services and provide search dashboards
  - Allow players to report others for abuse/violations
  - Store logs for admin moderation and auditing
  - Expose runtime feature flag toggles ([Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
    - Provide analytics dashboards for operators
    - Define moderation policies including profanity filters
  - Integrate Alertmanager for automated alerts
  - Deploy Fluent Bit sidecars to forward logs to Elasticsearch
  - Evaluate adopting a zero-trust network model for internal traffic
  - Create **Saga Dashboard** to inspect workflow states and failures
  - Integrate saga metrics and timeout recovery
  - Use saga orchestrator for multi-service admin operations (bans, content revocation)
  - Build role-based admin UI
