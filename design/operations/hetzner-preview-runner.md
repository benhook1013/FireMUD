# Hetzner Preview Runner

This note records the current self-hosted GitHub Actions runners on the Hetzner preview host and the isolation model used for preview work versus smoke CI.

## Current Facts

- Host: `77.42.29.156`
- Hostname: `ubuntu-8gb-hel1-1`
- SSH user currently used for access: `firemud`
- Root SSH is enabled for key-only login (`PermitRootLogin prohibit-password`).
- Current preview runner:
  - GitHub runner id: `21`
  - name: `hetzner-preview-runner`
  - install path: `/home/firemud/actions-runner`
  - labels:
    - `self-hosted`
    - `Linux`
    - `X64`
    - `preview`
- Current smoke CI runner:
  - GitHub runner id: `22`
  - name: `hetzner-ci-smoke-runner`
  - install path: `/home/github-runner/actions-runner`
  - labels:
    - `self-hosted`
    - `Linux`
    - `X64`
    - `ci-smoke`
    - `hetzner`

## Current Limitations

- The preview runner still lives under the general `firemud` account because it predates the smoke-CI isolation pass.
- The box is shared between preview work and smoke CI, so workload interference should be watched during the experiment.
- Docker is now installed for the smoke-CI path and `github-runner` is in the `docker` group.

## Target Shape

Use the dedicated `ci-smoke` runner for smoke-CI experimentation. Leave the preview runner and label alone unless preview workflow needs change.

Current isolated smoke-CI state:

- dedicated Unix user: `github-runner`
- runner files owned by that user
- Docker access granted deliberately to that user
- separate smoke-CI label: `ci-smoke`
- preview workflows and smoke CI do not share the same ambiguous label

## Box-Side Steps

Completed box-side steps:

1. Enabled root SSH for key-only access by changing `PermitRootLogin` from `no` to `prohibit-password`.
2. Installed Docker Engine and Docker Compose v2 on the host.
3. Created the dedicated `github-runner` user.
4. Added `github-runner` to the `docker` group.
5. Copied the runner binaries into `/home/github-runner/actions-runner`.
6. Registered `hetzner-ci-smoke-runner` with the `ci-smoke,hetzner` labels.
7. Installed and started the systemd service for the dedicated smoke runner.

Remaining operational validation:

- verify smoke workflow uses `ci-smoke`
- compare runtime and flake rate against `ubuntu-latest`
- watch for contention with the existing preview workload on the same host

## Repo-Side Follow-Up

When this setup changes, update:

- `.github/workflows/smoke.yml` if the CI runner label changes
- any preview workflows if their label strategy changes
- this note if the host, user, path, or labels change
