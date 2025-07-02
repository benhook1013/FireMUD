# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. Collects log data from all services and provides moderation capabilities for game operators.

## Architecture / Design Notes

- Aggregates logs using Fluent Bit and Elasticsearch.
- Prometheus collects metrics with Alertmanager handling alerts.
- Exposes admin endpoints for reviewing logs and applying moderation actions.

## Key Features

- Central log collection and search.
- Basic analytics dashboards for operators.
- Tools for banning or restricting accounts.
- Moderation policy definitions including profanity filters.

## Dependencies

- **External:** Elasticsearch, Prometheus, Grafana, and Alertmanager for storage, visualization, and alerting.

## Future Enhancements

- Role-based admin UI.
- Automated alerting for suspicious activity via Prometheus Alertmanager.
- Real-time analytics on game performance.
