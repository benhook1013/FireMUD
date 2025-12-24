Redis Docs Refactor Review – TODO

- [x] Map old Redis docs to new structure
- [x] Check preservation of key tables (prefix catalogs, reset matrices, key catalogs)
- [x] Verify quantitative examples and recommended settings (SLOs, size/latency budgets, TTL/profile examples)
- [x] Verify non-obvious edge case examples (failover vs cold start, reset scenarios, cluster behaviors)
- [x] Confirm normative “must/should” guidance is preserved
- [x] Prepare final report with missing/under-specified items
- [x] Reintroduce coordination metrics/thresholds and size budgets into `system-architecture-redis-operations.md`
- [x] Restore session TTL formula and payload budget details in `system-architecture-redis.md`
- [x] Add back session schema cleanup guidance under Redis operations
- [x] Restore cache prefix catalog entries (e.g., `character-cache:*`, `room:*`) in `system-architecture-redis-cache.md`
