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

Configuration values can also be set through profiles in `application.yml`.

## Tenant Handling and Dependencies

All world tables include a `tenantId` column to keep game data isolated.
Requests to this service must provide the tenant identifier, and downstream
calls include it when communicating with other services. The World Management
Service depends on:

- **Game Design Service** for procedural generation rules and versioned map data.
- **Game Session Service** to deliver room information and world event updates.
- **Automation & Scripting Service** to react to scheduled world changes.

## Travel and Pathfinding

The service exposes pathfinding utilities used by NPCs and movement validation.
`TravelService` performs Dijkstra-based searches across `room_exit` records to
return the shortest list of room IDs between two locations. This pathfinding is
invoked by the Game Session Service when a player moves or an NPC navigates.
