# Generated Architecture References

This directory contains checked-in references generated from canonical configuration metadata. Generated files are reviewable publication outputs, not independent hand-authored design authority.

- [Platform settings reference](./platform-settings-reference.md) - Human-readable settings groups, ownership, defaults, scopes, and runtime inspection surfaces.
- [Platform settings schema](./platform-settings-schema.json) - Machine-readable companion used by operator/admin and creator tooling.

After changing a surfaced settings definition, run `./gradlew updatePlatformSettingsDocs` and commit both generated outputs together. The owning settings contract remains [System Architecture: Settings Model](../system-architecture-settings-model.md).
