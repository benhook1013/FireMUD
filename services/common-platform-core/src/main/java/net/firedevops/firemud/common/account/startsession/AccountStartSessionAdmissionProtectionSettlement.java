package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProofCodec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed canonical Account settlement value containing the full Account evidence and exact Game
 * Session owner-proof bytes.
 *
 * <p>This carrier validates integrity and cross-binding only. It does not authenticate the producer
 * or compare the Account evidence with retained database rows.
 */
public final class AccountStartSessionAdmissionProtectionSettlement {
  public static final String SCHEMA = "account-start-session-admission-protection-settlement/v1";
  public static final int MAX_CANONICAL_BYTES = 24 * 1024 * 1024;

  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "canonicalAccountProtectionEvidenceBytesBase64",
          "canonicalGameSessionOwnerProofBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final OriginalStartSessionAdmissionTerminalResult result;
  private final AccountStartSessionAdmissionProtectionEvidence protectionEvidence;
  private final GameSessionCanonicalInitialAdmissionOwnerProof ownerProof;
  private final byte[] canonicalBytes;
  private final String digest;

  private AccountStartSessionAdmissionProtectionSettlement(
      OriginalStartSessionAdmissionTerminalResult result,
      byte[] exactAccountEvidenceBytes,
      byte[] exactOwnerProofBytes) {
    this.result = Objects.requireNonNull(result, "terminal result is required");
    this.protectionEvidence = result.request().protectionEvidence();
    this.ownerProof = result.ownerProof();
    if (!Arrays.equals(exactAccountEvidenceBytes, protectionEvidence.canonicalBytes())
        || !Arrays.equals(exactOwnerProofBytes, result.canonicalBytes())) {
      throw invalid("Settlement components differ from their exact canonical values");
    }
    this.canonicalBytes = encode(exactAccountEvidenceBytes, exactOwnerProofBytes);
    this.digest = digestPrefixed(canonicalBytes);
  }

  /** Creates a complete immutable settlement from the validated terminal result. */
  public static AccountStartSessionAdmissionProtectionSettlement create(
      OriginalStartSessionAdmissionTerminalResult result) {
    Objects.requireNonNull(result, "terminal result is required");
    byte[] accountEvidenceBytes = result.request().protectionEvidence().canonicalBytes();
    byte[] ownerProofBytes = result.canonicalBytes();
    GameSessionCanonicalInitialAdmissionOwnerProof proof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(ownerProofBytes);
    OriginalStartSessionAdmissionTerminalResult exactResult =
        new OriginalStartSessionAdmissionTerminalResult(
            new OriginalStartSessionAdmissionTerminalRequest(accountEvidenceBytes), proof);
    if (!Arrays.equals(ownerProofBytes, exactResult.canonicalBytes())) {
      throw invalid("Game Session owner proof bytes are not exact canonical bytes");
    }
    return new AccountStartSessionAdmissionProtectionSettlement(
        exactResult, accountEvidenceBytes, ownerProofBytes);
  }

  /** Strictly decodes one complete canonical settlement; decoding establishes no provenance. */
  public static AccountStartSessionAdmissionProtectionSettlement decode(
      byte[] exactCanonicalBytes) {
    byte[] stored = bounded(exactCanonicalBytes, MAX_CANONICAL_BYTES, "settlement");
    try {
      String json = strictUtf8(stored);
      requireCanonicalJson(stored, json);
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root);
      if (!SCHEMA.equals(string(root.get("schema"), "schema"))) {
        throw invalid("Unsupported Account admission protection settlement schema");
      }

      byte[] accountEvidenceBytes =
          decodeBase64(
              root.get("canonicalAccountProtectionEvidenceBytesBase64"),
              "canonicalAccountProtectionEvidenceBytesBase64",
              AccountStartSessionAdmissionProtectionEvidence.MAX_CANONICAL_BYTES);
      byte[] ownerProofBytes =
          decodeBase64(
              root.get("canonicalGameSessionOwnerProofBytesBase64"),
              "canonicalGameSessionOwnerProofBytesBase64",
              OriginalStartSessionAdmissionTerminalResult.MAX_CANONICAL_BYTES);
      AccountStartSessionAdmissionProtectionEvidence evidence =
          AccountStartSessionAdmissionProtectionEvidence.decode(accountEvidenceBytes);
      GameSessionCanonicalInitialAdmissionOwnerProof proof =
          GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(ownerProofBytes);
      OriginalStartSessionAdmissionTerminalResult result =
          new OriginalStartSessionAdmissionTerminalResult(
              new OriginalStartSessionAdmissionTerminalRequest(evidence.canonicalBytes()), proof);
      if (!Arrays.equals(ownerProofBytes, result.canonicalBytes())) {
        throw invalid("Game Session owner proof bytes are not canonical");
      }

      AccountStartSessionAdmissionProtectionSettlement decoded =
          new AccountStartSessionAdmissionProtectionSettlement(
              result, accountEvidenceBytes, ownerProofBytes);
      if (!MessageDigest.isEqual(stored, decoded.canonicalBytes)) {
        throw invalid("Account admission protection settlement members are inconsistent");
      }
      return decoded;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Account admission protection settlement is malformed");
    }
  }

  public AccountStartSessionAdmissionProtectionEvidence protectionEvidence() {
    return protectionEvidence;
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof ownerProof() {
    return ownerProof;
  }

  /**
   * Returns the existing exact terminal result, whose canonical bytes contain only the GS proof.
   */
  public OriginalStartSessionAdmissionTerminalResult result() {
    return result;
  }

  public GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome() {
    return ownerProof.outcome();
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** Returns {@code sha256:} plus lowercase SHA-256 of the complete canonical settlement object. */
  public String digest() {
    return digest;
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof AccountStartSessionAdmissionProtectionSettlement that
            && Arrays.equals(canonicalBytes, that.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private static byte[] encode(byte[] accountEvidenceBytes, byte[] ownerProofBytes) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
    root.put(
        "canonicalAccountProtectionEvidenceBytesBase64",
        Base64.getEncoder().encodeToString(accountEvidenceBytes));
    root.put(
        "canonicalGameSessionOwnerProofBytesBase64",
        Base64.getEncoder().encodeToString(ownerProofBytes));
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (canonical.length == 0 || canonical.length > MAX_CANONICAL_BYTES) {
        throw invalid("Account admission protection settlement exceeds its byte limit");
      }
      return canonical;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Account admission protection settlement cannot be encoded", impossible);
    }
  }

  private static byte[] decodeBase64(Object value, String field, int maximumBytes) {
    String encoded = string(value, field);
    long maximumEncodedLength = 4L * ((maximumBytes + 2L) / 3L);
    if (encoded.isEmpty() || encoded.length() > maximumEncodedLength) {
      throw invalid(field + " is empty or exceeds its byte limit");
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length == 0
          || decoded.length > maximumBytes
          || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
        throw invalid(field + " is not bounded canonical Base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is malformed or noncanonical Base64");
    }
  }

  private static void requireCanonicalJson(byte[] originalBytes, String json) throws IOException {
    if (!MessageDigest.isEqual(Rfc8785CanonicalJson.canonicalizeUtf8(json), originalBytes)) {
      throw invalid("Account admission protection settlement is not canonical RFC 8785 JSON");
    }
  }

  private static void requireExactFields(Map<String, Object> root) {
    if (root == null || !root.keySet().equals(ROOT_FIELDS)) {
      throw invalid("Account admission protection settlement has missing or unsupported fields");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Account admission protection settlement is not strict UTF-8");
    }
  }

  private static byte[] bounded(byte[] value, int maximum, String field) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw invalid(
          "Account admission protection " + field + " is empty or exceeds its byte limit");
    }
    return value.clone();
  }

  private static String digestPrefixed(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text)) {
      throw invalid(field + " must be a string");
    }
    return text;
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
