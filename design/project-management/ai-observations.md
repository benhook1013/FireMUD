# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-06-29`: WSL Docker smoke proof must use the native Linux CLI
  - Context: `dev-tools/verify-fresh-bootstrap.sh` and other source-built Compose proofs were intermittently hanging or behaving strangely on a WSL workstation even though `docker version` and simple image commands still worked.
  - Observation: a WSL `docker` wrapper that delegates to Windows `docker.exe` can look healthy enough to hide the real fault for a long time, but bind mounts can silently misbehave through that path. That makes source-built Compose proofs look flaky or repo-broken when the real issue is the local Docker CLI wiring.
  - Expected pattern: WSL-local FireMUD Docker work should use a native Linux Docker CLI pointed at `unix:///var/run/docker.sock`, and tooling/docs should treat Windows `docker.exe` wrappers inside WSL as unsupported for canonical smoke proof.

- `2026-06-29`: Service images must normalize boot-jar readability at image-build time
  - Context: after the WSL Docker path was corrected, the fresh-bootstrap stack exposed repeated `Unable to access jarfile /app/app.jar` failures across non-root Java service containers.
  - Observation: host-built jars can legitimately land with restrictive local modes such as `0600`. If service images inherit that mode directly, non-root runtime users fail at startup and the resulting container error looks like a runtime wiring problem instead of an artifact-packaging contract bug.
  - Expected pattern: service Dockerfiles should set explicit jar ownership and readable mode during image build, and any image that performs extra rename or pruning work should still finish by restoring the intended non-root runtime user.
