package net.firedevops.firemud.common.redis.contracts;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Canonical full-scope Redis key builder for Game Session region leases and local bridges. */
public final class GameplayRegionLeaseRedisKeyCodec {
  private GameplayRegionLeaseRedisKeyCodec() {}

  /** Builds an opaque, slot-safe tag from the complete durable tenant/instance/region tuple. */
  public static String tenantRegionTag(long tenantId, long gameInstanceId, String regionId) {
    requireScope(tenantId, gameInstanceId, regionId);
    try {
      ByteArrayOutputStream framed = new ByteArrayOutputStream();
      writeSegment(framed, "tenantRegionTag/v1");
      writeSegment(framed, Long.toString(tenantId));
      writeSegment(framed, Long.toString(gameInstanceId));
      writeSegment(framed, regionId);
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable for region Redis keys");
    }
  }

  /** Builds the canonical lease, epoch metadata, and session-binding keys for one region. */
  public static Keys keys(long tenantId, long gameInstanceId, String regionId) {
    String tag = tenantRegionTag(tenantId, gameInstanceId, regionId);
    String hashTag = "{" + tag + "}";
    return new Keys(
        tag,
        "tick-executor-lease:" + hashTag,
        "tick:" + hashTag + ":meta",
        "tick:" + hashTag + ":session-binding:");
  }

  /** Fails closed unless every key uses this codec's one complete region slot. */
  public static void requireOneRegionSlot(Keys keys) {
    Objects.requireNonNull(keys, "keys");
    String expected = "{" + keys.tenantRegionTag() + "}";
    if (!keys.tenantRegionTag().matches("[0-9a-f]{64}")
        || !keys.leaseKey().equals("tick-executor-lease:" + expected)
        || !keys.metadataKey().equals("tick:" + expected + ":meta")
        || !keys.sessionBindingPrefix().equals("tick:" + expected + ":session-binding:")) {
      throw new IllegalArgumentException("Region lease keys do not share the exact region slot");
    }
  }

  private static void requireScope(long tenantId, long gameInstanceId, String regionId) {
    if (tenantId <= 0L
        || gameInstanceId <= 0L
        || regionId == null
        || regionId.isEmpty()
        || regionId.codePointCount(0, regionId.length()) > 64
        || !regionId.equals(regionId.strip())
        || regionId.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Region lease scope is invalid");
    }
  }

  private static void writeSegment(ByteArrayOutputStream target, String value) {
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      target.writeBytes((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
      target.writeBytes(bytes);
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw new IllegalArgumentException("Region lease scope is invalid", malformed);
    }
  }

  public record Keys(
      String tenantRegionTag, String leaseKey, String metadataKey, String sessionBindingPrefix) {
    public Keys {
      Objects.requireNonNull(tenantRegionTag);
      Objects.requireNonNull(leaseKey);
      Objects.requireNonNull(metadataKey);
      Objects.requireNonNull(sessionBindingPrefix);
    }

    /** The admitted region binding key for one canonical actor UUID. */
    public String sessionBindingKey(UUID entityId) {
      return sessionBindingPrefix + requireEntityId(entityId);
    }

    /** Separate non-authoritative transition slot; it never aliases the admitted binding key. */
    public String sessionBindingPendingKey(UUID entityId) {
      return sessionBindingKey(entityId) + ":pending";
    }

    private static String requireEntityId(UUID entityId) {
      Objects.requireNonNull(entityId, "entityId");
      if (new UUID(0L, 0L).equals(entityId)) {
        throw new IllegalArgumentException("Region binding entityId must be non-nil");
      }
      return entityId.toString();
    }
  }
}
