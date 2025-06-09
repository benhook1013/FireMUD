# Logging & Admin Service

## Overview

Centralized logging and administration tools for the platform. Collects log data from all services and provides moderation capabilities for game operators.

## Architecture / Design Notes

- Aggregates logs and metrics using the ELK/Prometheus stack.
- Exposes admin endpoints for reviewing logs and applying moderation actions.

## Key Features

- Central log collection and search.
- Basic analytics dashboards for operators.
- Tools for banning or restricting accounts.

## Dependencies

- **External:** Elasticsearch, Prometheus, and Grafana for storage and visualization.

## Future Enhancements

- Role-based admin UI.
- Automated alerting for suspicious activity.
