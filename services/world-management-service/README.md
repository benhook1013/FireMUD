# World Management Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/world-management/v1](../../protos/world-management/v1)

## Running Locally

```bash
./gradlew :world-management-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

Once the service is running, the generated OpenAPI specification is available at
`http://localhost:8080/v3/api-docs` and the Swagger UI at
`http://localhost:8080/swagger-ui.html`.

Run `smoke-test.sh` while the service is running to verify the REST and gRPC contracts:

```bash
./smoke-test.sh
```

## Environment Variables

The service relies on standard Spring Boot properties for PostgreSQL and Redis
connections. Typical variables in development are:

| Variable | Purpose |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `SPRING_REDIS_HOST` | Redis hostname |
| `SPRING_REDIS_PORT` | Redis port |
| `FIREMUD_AUTH_JWT_SECRET` | JWT signing secret |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | JWT expiration in milliseconds |
| `WORLD_ROOM_CACHE_TTL_SECONDS` | Override default TTL for room cache |

Configuration values can also be set through profiles in `application.yml`.

### Test Data

Running with the `dev` profile automatically seeds a minimal region and rooms.
The `TestDataSeeder` inserts a demo region, zone, two rooms, and an exit when the
database tables are empty.

## Tenant Handling and Dependencies

All world tables include a `tenantId` column to keep game data isolated.
Requests to this service must provide the tenant identifier, and downstream
calls include it when communicating with other services. The World Management
Service depends on:

- **Game Design Service** for procedural generation rules and versioned map data.
- **Game Session Service** to deliver room information and world event updates.
- **Automation & Scripting Service** to react to scheduled world changes.

## Procedural Generation Rules

Generation parameters can be tweaked at runtime using a small REST API. Rules
are stored in the `generation_rule` table and apply per tenant.

- `POST /generation/rules` – create or update a rule
- `GET /generation/rules?tenantId=...` – list all rules for a tenant

These endpoints allow fine-grained control over terrain variation and other
generation options without redeploying the service.

## Travel and Pathfinding

The service exposes pathfinding utilities used by NPCs and movement validation.
`TravelService` performs Dijkstra-based searches across `room_exit` records to
return the shortest list of room IDs between two locations. This pathfinding is
invoked by the Game Session Service when a player moves or an NPC navigates.

Room data for frequently visited locations is cached in Redis using keys of the form `room:{tenantId}:{roomId}`. Entries expire after `world.room.cache-ttl-seconds` (default 60 seconds).
