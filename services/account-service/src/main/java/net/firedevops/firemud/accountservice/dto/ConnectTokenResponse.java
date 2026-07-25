package net.firedevops.firemud.accountservice.dto;

/** Browser-safe metadata returned after the connect token is set in a cookie. */
public record ConnectTokenResponse(
    long accountId,
    long tenantId,
    long gameInstanceId,
    String realmSlug,
    String connectScopeId,
    String jti,
    String requestId,
    String issuedAt,
    String expiresAt,
    boolean replayed) {

  public static ConnectTokenResponse from(ConnectTokenResult result) {
    return new ConnectTokenResponse(
        result.accountId(),
        result.tenantId(),
        result.gameInstanceId(),
        result.realmSlug(),
        result.connectScopeId(),
        result.jti(),
        result.requestId(),
        result.issuedAt(),
        result.expiresAt(),
        result.replayed());
  }
}
