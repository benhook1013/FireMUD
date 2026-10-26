package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Complete integrity-only settlement bytes for Account's existing V1003 admission protection.
 * Authentication and authoritative original-operation association belong to the owner services.
 */
public final class AccountStartSessionAdmissionProtectionSettlementEvidence {
  public static final String SCHEMA = "account-start-session-admission-protection-settlement/v1";
  public static final int MAX_CANONICAL_BYTES = 24 * 1024 * 1024;
  private static final int MAX_OWNER_PROOF_BYTES =
      AccountInitialAdmissionSettlementReadEvidence.MAX_OWNER_PROOF_BYTES;
  private static final String PROTECTION = "canonicalAccountProtectionEvidenceBytesBase64";
  private static final String TERMINAL = "canonicalGameSessionOwnerProofBytesBase64";
  private static final Set<String> FIELDS = Set.of("schema", PROTECTION, TERMINAL);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountStartSessionAdmissionProtectionEvidence protection;
  private final GameSessionCanonicalInitialAdmissionOwnerProof terminal;
  private final byte[] canonicalBytes;

  private AccountStartSessionAdmissionProtectionSettlementEvidence(
      AccountStartSessionAdmissionProtectionEvidence protection,
      GameSessionCanonicalInitialAdmissionOwnerProof terminal,
      byte[] canonicalBytes) {
    this.protection = protection;
    this.terminal = terminal;
    this.canonicalBytes = canonicalBytes.clone();
  }

  public static AccountStartSessionAdmissionProtectionSettlementEvidence create(
      AccountStartSessionAdmissionProtectionEvidence protection,
      GameSessionCanonicalInitialAdmissionOwnerProof terminal) {
    Objects.requireNonNull(protection, "Complete Account protection is required");
    Objects.requireNonNull(terminal, "Exact Game Session terminal is required");
    if (terminal.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.PENDING
        || !Arrays.equals(
            protection.request().worldAdmissionHoldIdentity().canonicalBytes(),
            terminal.holdIdentity().canonicalBytes())) {
      throw invalid("Terminal outcome or complete original World hold differs");
    }
    if (terminal.outcome() == GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED) {
      var hold = terminal.holdIdentity().request();
      if (Long.valueOf(Long.MAX_VALUE).equals(hold.expectedPriorPointerVersion())) {
        throw invalid("Committed terminal next pointer exceeds the supported integer range");
      }
      long expectedPointer =
          hold.expectedPriorPointerVersion() == null ? 1L : hold.expectedPriorPointerVersion() + 1L;
      if (terminal.committedPointerVersion() != expectedPointer) {
        throw invalid("Committed terminal must name the exact next pointer");
      }
    }
    byte[] proof = GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(terminal);
    requireBound(proof, MAX_OWNER_PROOF_BYTES, "Game Session terminal proof");
    try {
      byte[] bytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(
                  Map.of(
                      "schema",
                      SCHEMA,
                      PROTECTION,
                      Base64.getEncoder().encodeToString(protection.canonicalBytes()),
                      TERMINAL,
                      Base64.getEncoder().encodeToString(proof))));
      requireBound(bytes, MAX_CANONICAL_BYTES, "Account admission settlement");
      return new AccountStartSessionAdmissionProtectionSettlementEvidence(
          protection, terminal, bytes);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot encode Account admission settlement", failure);
    }
  }

  public static AccountStartSessionAdmissionProtectionSettlementEvidence decode(byte[] stored) {
    requireBound(stored, MAX_CANONICAL_BYTES, "Account admission settlement");
    try {
      Map<String, Object> fields = JSON.readValue(stored, new TypeReference<>() {});
      if (fields == null
          || !fields.keySet().equals(FIELDS)
          || fields.values().stream().anyMatch(value -> !(value instanceof String))
          || !SCHEMA.equals(fields.get("schema"))) {
        throw invalid("Closed Account admission settlement schema is required");
      }
      var result =
          create(
              AccountStartSessionAdmissionProtectionEvidence.decode(
                  decodeBase64(
                      (String) fields.get(PROTECTION),
                      AccountStartSessionAdmissionProtectionEvidence.MAX_CANONICAL_BYTES)),
              GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(
                  decodeBase64((String) fields.get(TERMINAL), MAX_OWNER_PROOF_BYTES)));
      if (!Arrays.equals(stored, result.canonicalBytes)) {
        throw invalid("Account admission settlement must be exact canonical bytes");
      }
      return result;
    } catch (JacksonException failure) {
      throw new IllegalArgumentException("Malformed Account admission settlement", failure);
    }
  }

  public AccountStartSessionAdmissionProtectionEvidence protection() {
    return protection;
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof terminal() {
    return terminal;
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome() {
    return terminal.outcome();
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static byte[] decodeBase64(String text, int maximum) {
    if (text.length() > 4L * ((maximum + 2L) / 3L)) {
      throw invalid("Settlement field exceeds its canonical byte bound");
    }
    byte[] decoded = Base64.getDecoder().decode(text);
    requireBound(decoded, maximum, "Settlement field");
    if (!Base64.getEncoder().encodeToString(decoded).equals(text)) {
      throw invalid("Settlement field must be canonical Base64");
    }
    return decoded;
  }

  private static void requireBound(byte[] bytes, int maximum, String name) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw invalid(name + " is missing or exceeds its byte bound");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
