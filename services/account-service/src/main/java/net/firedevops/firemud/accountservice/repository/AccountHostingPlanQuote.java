package net.firedevops.firemud.accountservice.repository;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Server-resolved immutable hosting quote, not caller/owner authentication or an entitlement. */
public record AccountHostingPlanQuote(
    String planCode,
    String providerPriceId,
    long providerQuantity,
    long maxActiveSessions,
    long maxConcurrentGameInstances,
    long maxStorageBytes) {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_PAYLOAD_BYTES = 4096;

  public AccountHostingPlanQuote {
    requirePlanCode(planCode);
    if (providerPriceId == null
        || providerPriceId.length() > 255
        || !providerPriceId.matches("price_[A-Za-z0-9]+")) {
      throw new IllegalArgumentException("Explicit Stripe price reference is required");
    }
    if (providerQuantity < 1
        || maxActiveSessions < 0
        || maxConcurrentGameInstances < 0
        || maxStorageBytes < 0) {
      throw new IllegalArgumentException(
          "Positive quantity and explicit nonnegative quotas are required");
    }
  }

  /** Fresh canonical bytes on every read; no mutable retained buffer escapes this value. */
  public byte[] payload() {
    // Decimal strings preserve every BIGINT exactly, including values beyond JSON's safe-integer
    // range. This is a versioned quote encoding, independent of the caller create-digest grammar.
    var fields =
        Map.of(
            "schemaVersion", "account-hosting-plan-quote/v1",
            "planCode", planCode,
            "providerPriceId", providerPriceId,
            "providerQuantity", Long.toString(providerQuantity),
            "maxActiveSessions", Long.toString(maxActiveSessions),
            "maxConcurrentGameInstances", Long.toString(maxConcurrentGameInstances),
            "maxStorageBytes", Long.toString(maxStorageBytes));
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
      if (bytes.length > MAX_PAYLOAD_BYTES) {
        throw new IllegalArgumentException("Hosting quote payload exceeds its retained byte bound");
      }
      return bytes;
    } catch (IOException failure) {
      throw new IllegalStateException("Hosting quote encoding failed", failure);
    }
  }

  public String digest() {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Exact canonical readback: neither altered bytes nor changed projection fields are accepted. */
  void requireRetained(byte[] retainedPayload, String retainedDigest) {
    if (retainedPayload == null
        || retainedPayload.length > MAX_PAYLOAD_BYTES
        || !Arrays.equals(payload(), retainedPayload)
        || !digest().equals(retainedDigest)) {
      throw new IllegalStateException("Retained hosting quote bytes/digest/fields disagree");
    }
  }

  static void requirePlanCode(String planCode) {
    if (planCode == null
        || planCode.isBlank()
        || planCode.length() > 255
        || !planCode.equals(planCode.strip())) {
      throw new IllegalArgumentException("Exact normalized plan code is required");
    }
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(planCode));
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException("Plan code must have an exact UTF-8 encoding", malformed);
    }
  }
}
