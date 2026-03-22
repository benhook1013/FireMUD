# Spring Cloud Gateway Task List

## Design

- [x] Finalize Spring Cloud Gateway design

## Core Gateway

> **Note:** Telnet and WebSocket login/session behavior (including `/ws/game/**`) is maintained in [the Login & Session vertical slice](vertical-slices/02-task-list-login-and-session-vertical-slice.md), and the Telnet-side protocol (`SESSION` envelope, headers, and semantics) is defined canonically in the TCP Proxy Service design’s **Telnet Session Envelope & Event Metrics** section. Please consult those docs for the current behaviour instead of duplicating protocol details here.

- [x] Handle API routing and request validation
- [ ] Terminate TLS and forward traffic to internal services using mTLS
- [x] Collect connection metrics and throttle abusive clients
- [x] Add baseline route configuration for Spring Cloud Gateway
- [x] Provide a lightweight `/ws/game/**` proxy stub (`services/tcp-proxy-service/src/test/.../stub/GatewayStubApplication.java`) for tcp-proxy cross-service tests so developers can exercise the gateway hop without starting the full production app.
- [ ] Automatically re-establish WebSocket tunnels on restart
- [ ] Trace WebSocket requests and responses for observability
- [ ] Wire TLS certificate watchers to reload credentials without downtime
- [ ] Relay event-driven game state updates to connected clients
- [ ] Confirm downstream admin/meta services use `firemud.auth` properties for token parsing; Spring Cloud Gateway must remain a dumb proxy that only enforces the presence of an Authorization header on protected routes
- [ ] Support horizontal scaling across gateway instances

## Dynamic Route Management

- [x] Create gateway route configuration files for all services
- [x] Create `GatewayController` endpoints for dynamic route management
- [x] Allow creation of custom gateway routes via API
- [x] Add gRPC `GatewayManagementService` for remote route configuration
- [ ] Allow route target overrides via `FIREMUD_SERVICES_*` env vars in line with `ServiceEndpointsProperties`
- [ ] Persist dynamic routes in the `route_config` table and clearly document how this persistent state composes with the baseline `routes-*.yml` files

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.
