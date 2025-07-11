# 🔗 Design Document for Game Design Service

The design for this service is located here:
[📄 Central Architecture: Game Design Service Design](../../../design/architecture/microservices/game-design-service/README.md)

This stub exists only to link to the real design document. **Do not add design details here.**
Additional details on template management can be found in
[game-templates.md](game-templates.md). Asset storage details are documented in
[asset-storage.md](asset-storage.md).

This stub exists to make the design easy to find from the service source tree.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /assets` – upload a binary asset for a tenant.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`game_design_service.proto`](../../../protos/game-design/v1/game_design_service.proto).
- `SaveRevision(SaveRevisionRequest) returns (SaveRevisionResponse)` – persists a design change.
- `PublishVersion(PublishVersionRequest) returns (PublishVersionResponse)` – publishes a frozen version.
- `ListVersions(ListVersionsRequest) returns (ListVersionsResponse)` – lists available versions.

```bash
grpcurl -plaintext localhost:6565 game_design.v1.GameDesignService/Ping
```

## Saga Participation

Publishing a game version is coordinated using the Saga utilities from
`firemud-common`. The `VersionServiceImpl` builds a workflow that first persists
the new version and then copies design data to downstream services. If any step
fails, previously executed actions are compensated so the database remains
consistent. See the
[Versioning & Runtime Configuration](../../../design/architecture/system-architecture-versioning-runtime.md)
document for the overall flow.

## Local Development Notes

`TestDataSeeder` populates a demo game, template, revision and version when the
`dev` Spring profile is active. A simple smoke-test script verifies both REST and
gRPC endpoints. Cross-service integration tests live under
`src/test/java/crossservice` and can be executed once dependent services are available.

This stub exists only to link to the real design document. **Do not add design details here.**
