# Trusted MinIO Source Images

The MinIO server and client images are built from upstream's GitHub-verified annotated release tags and pinned peeled source commits. The build checks both the remote tag object and resolved commit before compilation, uses the pinned Go 1.21.13 builder and Alpine runtime digests, and leaves each upstream `go.sum` read-only during compilation.

Pull request builds receive only `contents: read`. They build both images locally, verify server health, create the `firemud-assets` bucket with `mc`, and verify its anonymous policy is private. Successful develop-branch builds upload the exact smoke-tested images and image IDs. A separate `workflow_run` publisher checks that the source run was a successful push to the repository's `develop` default branch, validates the source commits and image IDs, then publishes those exact image bytes and attests each pushed digest.

The publisher summary contains the immutable GHCR references. Runtime manifests and Compose configuration should use those `name@sha256:...` references. The packages must be public for credential-free PR Smoke; the publisher attempts anonymous pulls after pushing and fails with the required GitHub Packages visibility action if either package is private.

The Alpine runtime includes BusyBox `wget` and `sh`, but not `curl`. Server health checks should use `busybox wget` (or another tool already present in the image), and the Compose `mc` setup can continue to override the entrypoint with `sh -c`.
