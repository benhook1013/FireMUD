# Issues Working

List of things in Design currently being worked on.

## 🛠 FireMUD Architecture: First-Pass Change Checklist

### 🎮 Game Rules & Policies

- [x] Clarify distinction between **mechanics rules** (e.g., damage, XP) and **moderation/social rules** (e.g., chat filters, bans).
- [x] Move ownership of **mechanics rules execution** to `Game Logic Service` (runtime).
- [x] Keep **definition of mechanics rules** in `Game Design Service`, but **not accessible at runtime**.
- [x] Move **moderation/social policies** to `Logging & Admin Service`.
  - Documented in [Service Responsibility Matrix](../architecture/service-responsibility-matrix.md).

### 🧬 Versioning Strategy

- [x] Remove assumption that runtime services fetch from `Game Design Service`.
- [x] Introduce a **publish step** in `Game Design Service` that triggers updates.
- [x] Each domain service versions its data **against a master `version_id`** published by the design service.
- [x] Optional: Introduce a `game_manifest` table (likely in `Game Session Service` or shared config) for version coordination.
  - See [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md).

### 🧪 Runtime Feature Flags

- [x] Remove runtime flags from `Game Design Service`.
- [x] Relocate runtime flags to `Game Session Service` (or a new `Runtime Config Service`).
- [x] Enable live editing of flags via `Logging & Admin Service`.
  - Behavior defined in [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md).

### 🧠 Role of Game Session Service

- [x] Expand responsibilities of `Game Session Service` to include:
  - [x] Game instance lifecycle management (start, stop, restart).
  - [x] Hosting live runtime configuration (e.g., flags).
  - [x] Holding current `version_id`/manifest for each live game.
  - [x] Clarify distinction between **player session management** and **game instance orchestration**.
  - Documented in [Game Session Service](../architecture/microservices/game-session-service/README.md).

### 🔗 Internal Communication & Security

- [x] Adopt **gRPC** for all service-to-service calls.
- [x] Secure internal traffic with **mTLS** certificates managed by Kubernetes.
  - See [Security Architecture](../architecture/system-architecture-security.md).

### 📦 Game Management Service Deprecation (Completed)

The former Game Management Service has been removed. Responsibilities were redistributed:

- Game creation and templates → `Game Design Service`.
- Game instance start/stop → `Game Session Service`.
- Game metadata/versioning → `Game Design Service`.
- Moderation policies → `Logging & Admin Service`.
- Ownership relations → `Account Service`.
