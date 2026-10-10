package net.firedevops.firemud.accountservice.service.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;

/** One canonical initial creator's immutable signing input, never an authentication result. */
final class AccountControlUiSigningSpec {
  private final UUID operationId;
  private final UUID requestId;
  private final UUID jti;
  private final Map<String, Object> claims;

  AccountControlUiSigningSpec(
      UUID operationId,
      UUID requestId,
      UUID jti,
      AccountControlUiAuthority.Snapshot source,
      Instant issuedAt,
      Instant expiresAt) {
    if (operationId == null
        || requestId == null
        || jti == null
        || source == null
        || issuedAt == null
        || expiresAt == null
        || issuedAt.getNano() != 0
        || expiresAt.getNano() != 0
        || !expiresAt.isAfter(issuedAt)
        || expiresAt.isAfter(issuedAt.plusSeconds(300))) {
      throw new IllegalArgumentException("Exact bounded control-ui signing input required");
    }
    this.operationId = operationId;
    this.requestId = requestId;
    this.jti = jti;
    var values = new java.util.LinkedHashMap<String, Object>();
    values.put("iss", "firemud-account-service");
    values.put("aud", ControlUiJwtProfileValidator.AUDIENCE);
    values.put("sub", source.actor().toString());
    values.put("accountId", source.actor().toString());
    values.put("jti", jti.toString());
    values.put("iat", issuedAt.getEpochSecond());
    values.put("nbf", issuedAt.getEpochSecond());
    values.put("exp", expiresAt.getEpochSecond());
    values.put("tokenGeneration", 1L);
    values.put("authorityTuple", source.authorityTuple());
    values.put("membershipVersion", source.membershipVersion());
    values.put("issuanceFence", source.issuanceFence());
    values.put("scopedRoles", Map.of(source.tenant().toString(), java.util.List.of("tenantAdmin")));
    ControlUiJwtProfileValidator.validateClaims(values, 1);
    if (AccountControlUiAuthority.canonical(values).length > 12288) {
      throw new IllegalArgumentException("Control-ui claim encoding exceeds its finite ceiling");
    }
    this.claims = Map.copyOf(values);
  }

  UUID operationId() {
    return operationId;
  }

  UUID requestId() {
    return requestId;
  }

  UUID jti() {
    return jti;
  }

  Map<String, Object> claims() {
    return claims;
  }

  @Override
  public String toString() {
    return "AccountControlUiSigningSpec[redacted]";
  }
}
