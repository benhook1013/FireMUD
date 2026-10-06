package net.firedevops.firemud.common.redis.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Canonical tenant-local key builder for gameplay session and reverse-index families. */
public final class GameplaySessionRedisKeyCodec {
  private static final UUID NIL = new UUID(0L, 0L);
  private static final Pattern TAG = Pattern.compile("gpt1-[0-9a-f]{64}");

  private GameplaySessionRedisKeyCodec() {}

  /** Builds the opaque, versioned hash-tag contents from the canonical tenant UUID only. */
  public static String tenantGameplayTag(UUID canonicalTenantId) {
    requireCanonicalUuid(canonicalTenantId, "canonicalTenantId");
    byte[] framed = segment("tenantGameplayTag/v1");
    byte[] tenant = segment(canonicalTenantId.toString());
    byte[] tuple = new byte[framed.length + tenant.length];
    System.arraycopy(framed, 0, tuple, 0, framed.length);
    System.arraycopy(tenant, 0, tuple, framed.length, tenant.length);
    try {
      return "gpt1-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tuple));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable for gameplay Redis keys");
    }
  }

  /** Produces only the documented tenant-local gameplay keys, all in one Redis Cluster slot. */
  public static Keys keys(
      UUID canonicalTenantId,
      long gameInstanceId,
      UUID sessionId,
      UUID playableStateNamespaceId,
      UUID characterId,
      UUID accountId) {
    if (gameInstanceId <= 0L) throw new IllegalArgumentException("gameInstanceId must be positive");
    requireCanonicalUuid(sessionId, "sessionId");
    requireCanonicalUuid(playableStateNamespaceId, "playableStateNamespaceId");
    requireCanonicalUuid(characterId, "characterId");
    requireCanonicalUuid(accountId, "accountId");
    String tag = tenantGameplayTag(canonicalTenantId);
    String hashTag = "{" + tag + "}";
    return new Keys(
        tag,
        "session:game:" + hashTag + ":" + gameInstanceId + ":" + sessionId,
        "session:game:index:character:"
            + hashTag
            + ":"
            + playableStateNamespaceId
            + ":"
            + characterId,
        "session:game:index:account-tenant:" + hashTag + ":" + accountId,
        "session:game:index:tenant:" + hashTag);
  }

  /** Runtime exact-slot validation for keys returned by this and future owner builders. */
  public static void requireOneTenantGameplaySlot(Keys keys) {
    Objects.requireNonNull(keys, "keys");
    String expected = "{" + keys.tenantGameplayTag() + "}";
    if (!TAG.matcher(keys.tenantGameplayTag()).matches()
        || !extractHashTag(keys.sessionKey()).equals(expected)
        || !extractHashTag(keys.characterIndexKey()).equals(expected)
        || !extractHashTag(keys.accountTenantIndexKey()).equals(expected)
        || !extractHashTag(keys.tenantIndexKey()).equals(expected)) {
      throw new IllegalArgumentException(
          "Gameplay session keys do not share the exact tenant slot");
    }
  }

  private static byte[] segment(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    byte[] prefix = (bytes.length + ":").getBytes(StandardCharsets.US_ASCII);
    byte[] segment = new byte[prefix.length + bytes.length];
    System.arraycopy(prefix, 0, segment, 0, prefix.length);
    System.arraycopy(bytes, 0, segment, prefix.length, bytes.length);
    return segment;
  }

  private static String extractHashTag(String key) {
    int open = key.indexOf('{');
    int close = key.indexOf('}', open + 1);
    if (open < 0
        || close <= open + 1
        || key.indexOf('{', open + 1) >= 0
        || key.indexOf('}', close + 1) >= 0) {
      return "";
    }
    return key.substring(open, close + 1);
  }

  private static void requireCanonicalUuid(UUID value, String field) {
    if (value == null || NIL.equals(value) || !UUID.fromString(value.toString()).equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  public record Keys(
      String tenantGameplayTag,
      String sessionKey,
      String characterIndexKey,
      String accountTenantIndexKey,
      String tenantIndexKey) {
    public Keys {
      Objects.requireNonNull(tenantGameplayTag);
      Objects.requireNonNull(sessionKey);
      Objects.requireNonNull(characterIndexKey);
      Objects.requireNonNull(accountTenantIndexKey);
      Objects.requireNonNull(tenantIndexKey);
    }
  }
}
