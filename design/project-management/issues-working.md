# Issues Working

List of things in Design currently being worked on.

## 🛠 FireMUD Architecture: First-Pass Change Checklist

### 🎮 Game Rules & Policies

- [ ] Review Game Session service for if not required (merge into other services)
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

- [ ] Remove runtime flags from `Game Design Service`.
- [ ] Relocate runtime flags to `Game Session Service` (or a new `Runtime Config Service`).
- [ ] Enable live editing of flags via `Logging & Admin Service`.

### 🧠 Role of Game Session Service

- [ ] Expand responsibilities of `Game Session Service` to include:
  - [ ] Game instance lifecycle management (start, stop, restart).
  - [ ] Hosting live runtime configuration (e.g., flags).
  - [ ] Holding current `version_id`/manifest for each live game.
- [ ] Clarify distinction between **player session management** and **game instance orchestration**.

### 📦 Deprecating Game Management Service

- [ ] Eliminate `Game Management Service` as a standalone service.
- [ ] Redistribute responsibilities:
  - [ ] Game creation, templates → `Game Design Service`.
  - [ ] Game instance start/stop → `Game Session Service`.
  - [ ] Game metadata/versioning → `Game Design Service`.
  - [ ] Moderation policies → `Logging & Admin Service`.
  - [ ] Ownership relations → `Account Service`.
