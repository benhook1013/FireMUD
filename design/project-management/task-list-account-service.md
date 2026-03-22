# Account Service Task List

## Account Management

- [ ] *(Login/session tasks maintained in the Login & Session vertical slice `design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md`.)*
- [x] Implement user registration and authentication (OAuth2, JWT)
- [x] Implement session management and persistent logins
- [x] Implement role-based access control (RBAC) for admins, moderators, and players
- [x] Enable external account linking (Google, Discord, Steam)
- [x] Implement profile system with achievements, game history, and social features
- [x] Implement player data export & deletion (GDPR compliance)
- [x] Expose JWKS endpoint for token verification
- [x] Use saga orchestrator for account creation workflow
- [x] Implement self-service account recovery
- [x] Add optional 2FA for admin and moderator roles
- [ ] Hash user passwords before storage using a strong algorithm like Argon2
- [ ] Track character ownership per account
- [ ] Implement account ban and suspension workflows with audit logging
- [ ] Provide web form and endpoints for players to submit ban appeals
- [ ] Wire TLS and JWT secret watchers to reload credentials without downtime
- [ ] Automate JWKS key rotation using cert-manager and update services to poll for changes

## Email & Notification System

- [x] Implement email verification & password resets
- [x] Implement in-game notification system for events & messages
- [x] Configure SMTP provider and test templates
- [x] Document email and notification design in `account-service/design/README.md`
- [x] Add asynchronous NotificationService components with gRPC endpoints

## Monetization & Payment Module

- [x] Integrate Stripe or similar for in-game purchases
- [x] Support subscriptions, one-time purchases, and donations
- [x] Enforce platform fee on transactions
- [x] Implement refund & chargeback handling
- [x] Use saga orchestrator for cross-service purchase workflows
- [x] Create `payment_transaction` and `subscription` entities in the Account Service
- [x] Add gRPC methods in `AccountService` for payments
- [x] Define proto contracts for payment and subscription flows in the account proto namespace
- [x] Add Flyway migration scripts for payment tables
- [x] Document monetization design in `account-service/design/README.md`
- [x] Implement virtual currency system (game-specific currencies)
- [x] Implement premium hosting tiers & features for game creators
- [x] Implement revenue-sharing system for game creators

## Reusable Microservice Checklist

These tasks apply to every FireMUD service unless noted otherwise.
