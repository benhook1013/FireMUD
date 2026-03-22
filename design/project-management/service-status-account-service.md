# Account Service Status

## Current Coverage

- Account registration, login, session management, and JWT issuance are implemented.
- Password hashing uses Argon2 in the live service implementation.
- Optional OTP-backed login flows exist and are consumed by Game Session.
- Profile management, external account linking, account recovery, notifications, and payment/subscription flows are implemented.
- JWKS publication is implemented for token verification consumers.

## Current Role In The Platform

- Owns account identity, credentials, JWT signing, and account-facing security flows.
- Supports gameplay login indirectly through Game Session rather than acting as the direct gameplay front door.
- Owns payment, subscription, and entitlement state for the platform.

## Partial / Stubbed / Deferred Areas

- Character ownership and account-to-character linkage should be treated as still evolving across Account, Entity, and gameplay flows.
- Ban/suspension/appeal workflows exist conceptually in the design set but are not yet represented as a clearly finished end-to-end operator flow in code and docs.
- Automated JWKS rotation and live secret-watcher reload behavior are still deferred.

## Planning Notes

- Do not use this file as a working checklist.
- Active implementation planning should happen in the vertical-slice docs under [`vertical-slices/`](./vertical-slices/).
