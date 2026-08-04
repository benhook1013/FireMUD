# Secret Compliance Evidence

This directory contains machine-readable environment provisioning-status records consumed by secret-compliance validation. The `.yaml` filenames are retained as the stable checked-in interface even though their current payloads use JSON syntax, which is valid YAML.

- [Hobby self-hosted](./hobby-self-hosted.yaml)
- [Staging](./staging.yaml)
- [Production](./production.yaml)

The records are operational evidence, not the authority for credential design. See [Environment and secrets](../../architecture/infrastructure/environment-and-secrets.md) for the canonical secret-delivery contract.
