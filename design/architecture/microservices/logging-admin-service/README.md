# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. Collects log data from all services and provides moderation capabilities for game operators.

## Architecture / Design Notes

Uses the common stack outlined in [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and exposes admin endpoints for reviewing logs and applying moderation actions.

## Key Features

- Central log collection and search.
- Basic analytics dashboards for operators.
- Tools for banning or restricting accounts.
- Moderation policy definitions including profanity filters.
- UI and APIs for toggling runtime feature flags.

## Dependencies

- **External:** Elasticsearch, Prometheus, Grafana, and Alertmanager for storage, visualization, and alerting.

## 📚 Related Documentation

See [Logging & Monitoring](../../system-architecture-logging-monitoring.md) for details on the shared observability stack.

## Future Enhancements

- Role-based admin UI.
- Automated alerting for suspicious activity via Prometheus Alertmanager.
- Real-time analytics on game performance.
