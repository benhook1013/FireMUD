package net.firedevops.firemud.accountservice.dto;

/** Result of issuing a short-lived gameplay connect token. */
public record ConnectTokenResult(
    long accountId,
    long tenantId,
    long gameInstanceId,
    String realmSlug,
    String connectScopeId,
    String connectToken,
    String jti,
    String requestId,
    String issuedAt,
    String expiresAt,
    boolean replayed) {}
