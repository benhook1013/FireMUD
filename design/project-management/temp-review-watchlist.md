# Temporary Review Watchlist

Use this file as the temporary tracker for review-driven cleanup discovered while pre-06 and preview work is still active. Add new issues as soon as they are identified so they do not remain only in chat context.

## Fix Now

- [x] Account gameplay admission is now tenant-local: `getTenantMembershipForRuntime(...)` only allows membership when the account tenant matches the requested tenant, and authentication no longer falls back to global tenant `0` accounts.
  - Relevant files:
    - `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java`
- [x] Game session lifecycle commits `RUNNING` / `STOPPED` rows before required Redis/session-state propagation and downstream coordination complete.
  - Relevant files:
    - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/GameInstanceServiceImpl.java`
- [x] Reconnect screen buffering is not concurrency-safe; append still does a read/modify/write of one Redis JSON blob and can lose transcript lines under concurrent writers.
  - Relevant files:
    - `services/common-data-runtime/src/main/java/net/firedevops/firemud/cache/RedisScreenBufferService.java`

## Fix Soon

- [x] Redis-based admission/rate-limit paths no longer use non-atomic `INCR` then `EXPIRE` patterns, and IP admission now uses an atomic reservation path instead of splitting acceptance from registration.
  - Relevant files:
    - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/SessionRateLimiterImpl.java`
    - `services/game-session-service/src/main/java/net/firedevops/firemud/gamesession/service/impl/IpConnectionLimiterImpl.java`
    - `services/automation-scripting-service/src/main/java/net/firedevops/firemud/automationscripting/service/quota/ScriptQuotaServiceImpl.java`
- [x] World creation saga no longer reports success when the design fetch fails because `copyDesignData(...)` now lets Game Design client failures fail the saga instead of silently creating a starter region.
  - Relevant files:
    - `services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldCreationServiceImpl.java`
- [x] Shared Redis autoconfiguration now supports production Redis settings such as password, TLS, and database index.
  - Relevant files:
    - `services/common-data-runtime/src/main/java/net/firedevops/firemud/common/config/RedisProperties.java`
    - `services/common-data-runtime/src/main/java/net/firedevops/firemud/common/config/DatabaseAutoConfiguration.java`

## Refactor And Hygiene

- [x] Account/payment flows now keep external Stripe, email, and notification calls out of the open DB transaction boundary.
  - Relevant files:
    - `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/PaymentServiceImpl.java`
    - `services/account-service/src/main/java/net/firedevops/firemud/accountservice/service/impl/AccountServiceImpl.java`
- [ ] Gateway management gRPC still bridges reactive flows by manually `subscribe()`-ing inside unary RPC handlers.
  - Relevant files:
    - `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/service/impl/GatewayManagementGrpcService.java`

## No Longer A Primary Concern

- [x] Gateway-owned header trust and first-party connect-token replay protection are materially improved enough that they should not be prioritized as current security regressions.
  - Relevant files:
    - `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/HeaderTrustFilter.java`
    - `services/spring-cloud-gateway/src/main/java/net/firedevops/firemud/springcloudgateway/filter/GameplayHandshakeFilter.java`
