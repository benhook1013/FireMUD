# Account Service Proto (v1)

This directory houses the version 1 protocol buffer definitions for the Account Service. These
files define registration, authentication, profile, and session management APIs. The
initial schema provided only a `Ping` RPC for connectivity testing. This version adds
additional account management calls and a separate `PaymentService` for billing operations.
Version 1 also introduces an asynchronous `NotificationService` for delivering account
related messages.

Generate Java stubs via `./gradlew generateProto` from the repository root.

For service usage see the [Account Service design](../../../design/architecture/microservices/account-service/README.md).
