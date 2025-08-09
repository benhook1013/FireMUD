# Docker Resources

Contains base Dockerfiles and the Compose stack used for local development.
Run commands from the repository root.
Copy `.env.sample` to `.env` in the repository root to override default service credentials.

Start services with:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml up --build -d
```

Stop and remove the stack with:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.override.yml down
```
