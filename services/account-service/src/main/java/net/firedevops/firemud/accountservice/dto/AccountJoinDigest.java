package net.firedevops.firemud.accountservice.dto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical exact-byte digests for retained public-production scope and JOIN attempts. */
public final class AccountJoinDigest {
  private AccountJoinDigest() {}

  /**
   * The only entitlement-availability value from which a v2 policy-bound request digest is made.
   * Callers still own provenance and freshness checks for the value they provide.
   */
  public enum EntitlementAvailabilityV2 {
    AVAILABLE,
    UNAVAILABLE
  }

  /** Computes the policy-independent v2 digest of a complete public-production scope preimage. */
  public static String scopeV2(CanonicalJoinScopeV2 scope) {
    requireScopeV2(scope);
    ByteArrayOutputStream output = v2Header("account-join-scope/v2", "PUBLIC_PRODUCTION");
    appendScopeV2(output, scope);
    return hashV2(output.toByteArray());
  }

  /** Computes a policy-independent v2 JOIN intent digest. */
  public static String intentV2(
      String requestId, CanonicalJoinScopeV2 scope, String callerBinding) {
    requireScopeV2(scope);
    ByteArrayOutputStream output = v2Header("account-join-intent/v2", "JOIN");
    appendSegmentV2(output, requireV2Text("requestId", requestId));
    appendSegmentV2(output, requireV2Text("callerBinding", callerBinding));
    appendScopeV2(output, scope);
    return hashV2(output.toByteArray());
  }

  /**
   * Computes a v2 policy-bound JOIN request digest only for explicitly available entitlement
   * evidence. The caller must establish the evidence's authority, target binding, and freshness.
   */
  public static String requestV2(
      CanonicalJoinScopeV2 scope,
      String callerBinding,
      EntitlementAvailabilityV2 availability,
      Boolean allowPublicJoin,
      Long entitlementVersion) {
    requireScopeV2(scope);
    if (availability != EntitlementAvailabilityV2.AVAILABLE) {
      throw new IllegalArgumentException(
          "A v2 JOIN digest requires available entitlement evidence");
    }
    if (allowPublicJoin == null || entitlementVersion == null || entitlementVersion <= 0L) {
      throw new IllegalArgumentException(
          "A v2 JOIN digest requires exact available policy evidence");
    }
    ByteArrayOutputStream output = v2Header("account-join-request/v2", "JOIN");
    appendSegmentV2(output, requireV2Text("callerBinding", callerBinding));
    appendScopeV2(output, scope);
    appendSegmentV2(output, "AVAILABLE");
    appendSegmentV2(output, allowPublicJoin.toString());
    appendSegmentV2(output, entitlementVersion.toString());
    return hashV2(output.toByteArray());
  }

  public static String scope(VerifiedJoinScope scope) {
    return hash(
        "scopeDigestVersion=v1\n"
            + field("accountId", scope.accountId())
            + "targetClass=PUBLIC_PRODUCTION\n"
            + field("connectScopeId", scope.connectScopeId())
            + field("tenantId", scope.tenantId())
            + field("realmId", scope.realmId().toString())
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
      Boolean allowPublicJoin,
      Long entitlementVersion) {
    if (allowPublicJoin == null || entitlementVersion == null || entitlementVersion <= 0L) {
      throw new IllegalArgumentException("A JOIN policy digest requires exact available evidence");
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
            + field("entitlementAuthorityAvailability", "AVAILABLE")
            + field("allowPublicJoin", allowPublicJoin)
            + field("entitlementVersion", entitlementVersion));
  }

  public static String intent(String requestId, VerifiedJoinScope scope, String callerBinding) {
    return hash(
        "joinIntentDigestVersion=v1\n"
            + "operationKind=JOIN\n"
            + field("requestId", requestId)
            + field("accountId", scope.accountId())
            + field("callerBinding", callerBinding)
            + field("connectScopeId", scope.connectScopeId())
            + field("scopeDigest", scope.snapshotDigest())
            + field("tenantId", scope.tenantId())
            + field("worldSlug", scope.worldSlug())
            + field("realmSlug", scope.realmSlug())
            + field("realmId", scope.realmId().toString())
            + field("playableStateNamespaceId", scope.playableStateNamespaceId())
            + field("playableStateScope", scope.playableStateScope())
            + field("gameInstanceId", scope.gameInstanceId())
            + field("catalogRevision", scope.catalogRevision())
            + field("pointerVersion", scope.pointerVersion()));
  }

  public static String tokenHash(String connectScopeId) {
    return hash(connectScopeId);
  }

  private static ByteArrayOutputStream v2Header(String domain, String operationKind) {
    ByteArrayOutputStream output = new ByteArrayOutputStream(512);
    appendSegmentV2(output, domain);
    appendSegmentV2(output, "2");
    appendSegmentV2(output, operationKind);
    return output;
  }

  private static void appendScopeV2(ByteArrayOutputStream output, CanonicalJoinScopeV2 scope) {
    appendSegmentV2(output, scope.connectScopeId());
    appendSegmentV2(output, scope.accountId().toString());
    appendSegmentV2(output, scope.tenantId().toString());
    appendSegmentV2(output, scope.realmId().toString());
    appendSegmentV2(output, scope.tenantSlug());
    appendSegmentV2(output, scope.worldSlug());
    appendSegmentV2(output, scope.realmSlug());
    appendSegmentV2(output, scope.playableStateNamespaceId().toString());
    appendSegmentV2(output, scope.playableStateScope());
    appendSegmentV2(output, scope.gameInstanceId().toString());
    appendSegmentV2(output, Long.toString(scope.catalogRevision()));
    appendSegmentV2(output, Long.toString(scope.pointerVersion()));
    appendSegmentV2(output, scope.evaluatedAt());
    appendSegmentV2(output, scope.connectScopeExpiresAt());
  }

  private static void appendSegmentV2(ByteArrayOutputStream output, String value) {
    byte[] bytes = encodeUtf8V2(value);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static String requireV2Text(String field, String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    encodeUtf8V2(value);
    return value;
  }

  private static byte[] encodeUtf8V2(String value) {
    if (value == null) {
      throw new IllegalArgumentException("A v2 framed UTF-8 value is required");
    }
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(
          "A v2 framed value must contain well-formed Unicode", exception);
    }
  }

  private static void requireScopeV2(CanonicalJoinScopeV2 scope) {
    if (scope == null) {
      throw new IllegalArgumentException("A canonical v2 JOIN scope is required");
    }
  }

  private static String hashV2(byte[] preimage) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(preimage));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
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
