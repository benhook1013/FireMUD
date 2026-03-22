# Core Alertmanager Snippets

This file is now the index for the core FireMUD alert snippet split. The domain-specific rule sets live in sibling files so the alert families stay readable and easier to maintain. These snippets complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

- [redis-alerts-snippets.md](./redis-alerts-snippets.md) – Redis tail-loss and coordination health alerts.
- [tick-alerts-snippets.md](./tick-alerts-snippets.md) – Tick execution, ledger backlog, cleanup lag, and replay fairness alerts.
- [backup-alerts-snippets.md](./backup-alerts-snippets.md) – Backup pipeline, pause window, and restore-drill health alerts.
- [player-experience-alerts-snippets.md](./player-experience-alerts-snippets.md) – Player-centric SLO alerts for login, command latency, chat delivery, and entry-path availability.
- [observability-stack-alerts-snippets.md](./observability-stack-alerts-snippets.md) – Alertmanager, Prometheus, OTel, Elasticsearch, Jaeger, Fluent Bit, and Grafana health plus smoke-test alerts.
