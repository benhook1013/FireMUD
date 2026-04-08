# Runtime Hardening Worklist

Created to track the current audit batch while the bounded fixes land and the broader changes stay visible.

## Done

- [x] Share gateway and game-session JWT secrets in local/smoke compose to stop first-party WebSocket readiness failures.
- [x] Make tick lock release ownership-safe so an expired worker cannot delete a newer worker's lock.
- [x] Make gateway route deletion report outcome from the route writer instead of swallowing errors and trusting the local cache.
- [x] Remove the silent per-pod replay-protection fallback for first-party WebSocket handshakes outside explicit dev/test behavior.

## Next

- [ ] Convert admin gRPC authorization failures from transport `PERMISSION_DENIED` exceptions into normal response `ErrorDetail` payloads with the usual metrics/logging/span path.
- [ ] Rework session lifecycle/runtime-state writes so `RUNNING` and `STOPPED` are not committed as best-effort DB truth while Redis/runtime side effects can silently fail afterward.
- [ ] Add rejection handling/metrics/backpressure for tick scheduling fan-out versus the bounded async executor.

## Notes

- The remaining unchecked `./gradlew check` failure on this branch is the pre-existing `:game-session-service:crossServiceTest` Testcontainers startup issue, not part of this runtime hardening batch.
