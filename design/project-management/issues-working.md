# Issues Working

List of things in Design currently being worked on.

## 🛠 FireMUD Architecture: First-Pass Change Checklist

### 🎮 Game Rules & Policies

- [ ] Clarify distinction between **mechanics rules** (e.g., damage, XP) and **moderation/social rules** (e.g., chat filters, bans).
- [ ] Move ownership of **mechanics rules execution** to `Game Logic Service` (runtime).
- [ ] Keep **definition of mechanics rules** in `Game Design Service`, but **not accessible at runtime**.
- [ ] Move **moderation/social policies** to `Logging & Admin Service`.

### 🧬 Versioning Strategy

- [ ] Remove assumption that runtime services fetch from `Game Design Service`.
- [ ] Introduce a **publish step** in `Game Design Service` that triggers updates.
- [ ] Each domain service versions its data **against a master `version_id`** published by the design service.
- [ ] Optional: Introduce a `game_manifest` table (likely in `Game Session Service` or shared config) for version coordination.

### 🧪 Runtime Feature Flags

- [ ] Keep runtime flag definitions in `Game Design Service`.
- [ ] Store and manage runtime flag values in `Game Session Service`.
- [ ] Enable live editing of flags via `Logging & Admin Service`.

### 🧠 Role of Game Session Service

- [ ] Expand responsibilities of `Game Session Service` to include:
  - [ ] Game instance lifecycle management (start, stop, restart).
  - [ ] Hosting live runtime configuration (e.g., flags).
  - [ ] Holding current `version_id`/manifest for each live game.
  - [ ] Clarify distinction between **player session management** and **game instance orchestration**.

### 🔗 Internal Communication & Security

- [ ] Adopt **gRPC** for all service-to-service calls.
- [ ] Secure internal traffic with **mTLS** certificates managed by Kubernetes.

### 📦 Game Management Service Deprecation (Completed)

The former Game Management Service has been removed. Responsibilities were redistributed:

- Game creation and templates → `Game Design Service`.
- Game instance start/stop → `Game Session Service`.
- Game metadata/versioning → `Game Design Service`.
- Moderation policies → `Logging & Admin Service`.
- Ownership relations → `Account Service`.
