# Spring Cloud Gateway Status

## Current Coverage

- Core HTTP/gateway routing, `/ws/game/**` handling, JWT/header trust filtering, and connection metrics are implemented.
- Lightweight gateway stub support exists for cross-service gameplay testing.
- Dynamic route management surfaces and baseline route configuration are present.
- The canonical session HTTP edge now keeps only `GET /api/session/ping` on the public gateway inventory; mutating Game Session `/sessions*` control-plane routes remain owner-side privileged hooks instead of riding a blanket public `/api/session/**` forwarder.
- Public `/api/{service}/internal/**` and `/api/{service}/actuator/**` subtrees are now blocked at the gateway boundary instead of being forwarded by the coarse public family matcher.

## Current Role In The Platform

- Acts as the browser/websocket front door for first-party HTTP and gameplay traffic.
- Proxies gameplay traffic into Game Session rather than owning gameplay semantics itself; `LOGIN` / `PLAY` semantics remain downstream in Game Session.
- Provides test harness value through the lightweight gateway stub path.

## Partial / Stubbed / Deferred Areas

- Some production-hardening concerns remain, especially around TLS lifecycle, restart/reconnect behavior, and route persistence/overrides.
- Gateway should remain thin; future work should avoid pulling gameplay logic into it.
- Real operational readiness should be tracked as infrastructure hardening, not as gameplay-slice scope.
- The main remaining edge-contract work is keeping future route additions on the same explicit inventory model and avoiding drift back to broad family forwarding.

## Planning Notes

- Active work should only touch Gateway through vertical slices when player ingress behavior changes.
