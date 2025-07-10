# Game Logic Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/game-logic/v1](../../protos/game-logic/v1)

The service exposes a minimal REST endpoint for testing command parsing:

```bash
curl -X POST http://localhost:8080/command -d "attack goblin"
```

## Running Locally

```bash
./gradlew :game-logic-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```
