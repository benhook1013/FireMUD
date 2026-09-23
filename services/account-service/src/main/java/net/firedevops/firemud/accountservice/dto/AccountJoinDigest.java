package net.firedevops.firemud.accountservice.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical exact-byte digests for retained public-production scope and JOIN attempts. */
public final class AccountJoinDigest {
  private AccountJoinDigest() {}

  public static String scope(VerifiedJoinScope scope) {
    return hash(
        "scopeDigestVersion=v1\n"
            + field("accountId", scope.accountId())
            + "targetClass=PUBLIC_PRODUCTION\n"
            + field("connectScopeId", scope.connectScopeId())
            + field("tenantId", scope.tenantId())
            + field("realmId", scope.realmId())
            + field("worldSlug", scope.worldSlug())
            + field("realmSlug", scope.realmSlug())
            + field("playableStateNamespaceId", scope.playableStateNamespaceId())
            + field("playableStateScope", scope.playableStateScope())
            + field("gameInstanceId", scope.gameInstanceId())
            + field("catalogRevision", scope.catalogRevision())
            + field("pointerVersion", scope.pointerVersion())
            + field("evaluatedAt", scope.evaluatedAt())
            + field("connectScopeExpiresAt", scope.connectScopeExpiresAt()));
  }

  public static String request(
      VerifiedJoinScope scope,
      String callerBinding,
      String entitlementAuthorityAvailability,
      Boolean allowPublicJoin,
      Long entitlementVersion) {
    if (!"AVAILABLE".equals(entitlementAuthorityAvailability)
        && !"UNAVAILABLE".equals(entitlementAuthorityAvailability)
        && !"NOT_EVALUATED".equals(entitlementAuthorityAvailability)) {
      throw new IllegalArgumentException("Entitlement authority availability is invalid");
    }
    if ("AVAILABLE".equals(entitlementAuthorityAvailability)
        != (allowPublicJoin != null && entitlementVersion != null && entitlementVersion > 0L)) {
      throw new IllegalArgumentException("Policy evidence must match authority availability");
    }
    return hash(
        "joinDigestVersion=v1\n"
            + "operationKind=JOIN\n"
            + field("accountId", scope.accountId())
            + field("callerBinding", callerBinding)
            + field("tenantId", scope.tenantId())
            + field("worldSlug", scope.worldSlug())
            + field("realmSlug", scope.realmSlug())
            + field("playableStateNamespaceId", scope.playableStateNamespaceId())
            + field("playableStateScope", scope.playableStateScope())
            + field("gameInstanceId", scope.gameInstanceId())
            + field("connectScopeId", scope.connectScopeId())
            + field("catalogRevision", scope.catalogRevision())
            + field("pointerVersion", scope.pointerVersion())
            + field("entitlementAuthorityAvailability", entitlementAuthorityAvailability)
            + ("AVAILABLE".equals(entitlementAuthorityAvailability)
                ? field("allowPublicJoin", allowPublicJoin)
                    + field("entitlementVersion", entitlementVersion)
                : ""));
  }

  public static String tokenHash(String connectScopeId) {
    return hash(connectScopeId);
  }

  private static String field(String name, Object value) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    String wire = value.toString();
    if (wire.isEmpty()
        || wire.indexOf('=') >= 0
        || wire.indexOf('\n') >= 0
        || wire.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(name + " has an invalid canonical wire value");
    }
    return name + "=" + wire + "\n";
  }

  private static String hash(String preimage) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(preimage.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
