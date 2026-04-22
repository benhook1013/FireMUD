# Docker Resources

Contains base Dockerfiles and the Compose stack used for local development.
Run commands from the repository root.
Copy `.env.sample` to `.env` in the repository root to override default service credentials.

Start services with:

```bash
dev-tools/build-compose-service-jars.sh
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml up --build -d
```

Stop and remove the stack with:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down
```

For canonical local smoke/bootstrap proof, prefer the repo scripts instead of ad hoc compose sequences:

```bash
dev-tools/verify-fresh-bootstrap.sh
dev-tools/verify-restart-state.sh
SMOKE_IMAGE_TAG=<tag> dev-tools/verify-smoke-images.sh
```
