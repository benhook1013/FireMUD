# Spring Cloud Gateway Status

## Current Coverage

- Core HTTP/gateway routing, `/ws/game/**` handling, JWT/header trust filtering, and connection metrics are implemented.
- Lightweight gateway stub support exists for cross-service gameplay testing.
- Dynamic route management surfaces and baseline route configuration are present.
- The canonical `/api/session/**` family now routes as HTTP control-plane traffic rather than through a `ws://` upstream, keeping the live gateway config aligned with the documented `/ws/game/**` versus `/api/session/**` split.

## Current Role In The Platform

- Acts as the browser/websocket front door for first-party HTTP and gameplay traffic.
- Proxies gameplay traffic into Game Session rather than owning gameplay semantics itself; `LOGIN` / `PLAY` semantics remain downstream in Game Session.
- Provides test harness value through the lightweight gateway stub path.

## Partial / Stubbed / Deferred Areas

- Some production-hardening concerns remain, especially around TLS lifecycle, restart/reconnect behavior, and route persistence/overrides.
- Gateway should remain thin; future work should avoid pulling gameplay logic into it.
- Real operational readiness should be tracked as infrastructure hardening, not as gameplay-slice scope.
- The main remaining edge-contract gap is still deny-by-default enforcement for internal-only subtrees under public `/api/{service}/**` families, not basic route-family or protocol-shape confusion.

## Planning Notes

- Active work should only touch Gateway through vertical slices when player ingress behavior changes.
