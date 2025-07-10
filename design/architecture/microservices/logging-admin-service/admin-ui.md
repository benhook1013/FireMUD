# 🖥️ Role-Based Admin UI

Moderators and administrators interact with the service through a lightweight React interface. The UI authenticates via JWTs issued by the Account Service and enforces permissions based on the `globalRoles` and `scopedRoles` claims.

## Features

- Search and filter logs with Kibana-like syntax.
- Review player reports and apply moderation actions.
- Toggle runtime feature flags for a specific tenant.
- Inspect saga workflows and retry failed steps.

The UI is packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls are protected by the existing security interceptors.
