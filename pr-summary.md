## Summary
- rename the per-service planning docs from `task-list-*.md` to `service-status-*.md`
- convert those files from stale backlogs into short implementation-status summaries
- make `design/project-management/task-list.md` a thin planning index instead of a historical mega-backlog
- remove the redundant `core-requirements-summary.md` file and trim `design-assumptions.md` into a short orientation note

## What changed
- added `design/project-management/service-status-*.md` files for:
  - account-service
  - automation-scripting-service
  - entity-management-service
  - game-design-service
  - game-logic-service
  - game-session-service
  - logging-admin-service
  - social-groups-service
  - spring-cloud-gateway
  - tcp-proxy-service
  - web-client
  - world-management-service
- removed the old per-service `task-list-*.md` files they replace
- rewrote `design/project-management/task-list.md` as a top-level planning index that points to:
  - active vertical-slice task docs
  - per-service status summaries
- updated references in project-management, architecture, and vertical-slice docs to use the new `service-status-*` naming
- removed `design/project-management/core-requirements-summary.md`
- updated references to point back to `design/project-management/core-requirements.md`
- trimmed `design/project-management/design-assumptions.md` into a short orientation/defaults snapshot instead of a duplicate architecture summary

## Validation
- `./gradlew linkCheck lintMarkdown`

## Notes
- active implementation planning now lives in `design/project-management/vertical-slices/`
- per-service project-management docs are now narrative status summaries rather than working TODO lists
