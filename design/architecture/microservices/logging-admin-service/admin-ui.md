# 🖥️ Role-Based Admin UI

Moderators and administrators interact with the service through a lightweight React interface. Credentials are exchanged with the Account Service, which issues JWTs for backend calls. These tokens remain server-side and permissions are enforced using the `globalRoles` and `scopedRoles` claims.

## Features

- Search and filter logs with Kibana-like syntax.
- Review player reports and apply moderation actions.
- Toggle runtime feature flags for a specific tenant.
- Inspect saga workflows and retry failed steps.

The UI is packaged as a separate web module served by the Logging & Admin Service. Styling relies on Material‑UI components, and all API calls are protected by the existing security interceptors.
