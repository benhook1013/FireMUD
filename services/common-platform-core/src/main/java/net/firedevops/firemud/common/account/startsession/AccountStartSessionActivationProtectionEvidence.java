package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical integrity evidence for one Account StartSession activation-protection value.
 *
 * <p>This carrier retains the full request, independently acquired Account protection identity,
 * exact original capture reference, and complete canonical source-evidence vector. It proves only
 * supplied-byte integrity and structural binding; it does not authenticate Account or World, prove
 * owner currentness, or establish write exclusion.
 */
public final class AccountStartSessionActivationProtectionEvidence {
  public static final String SCHEMA = "account-start-session-activation-protection-evidence/v1";

  /**
   * Aggregate closed-envelope bound: Base64 expansion of the 144 MiB full request is 192 MiB; the
   * existing 4 MiB source-vector and 256 KiB capture-reference caps add about 5.7 MiB after Base64
   * expansion, leaving room for the fixed JSON envelope. This is a bound on this new Account value
   * only, not a transport limit.
   */
  public static final int MAX_CANONICAL_BYTES = 200 * 1024 * 1024;

  private static final String OPERATION_IDENTITY_SCHEMA =
      "account-start-session-activation-protection-evidence-operation-identity/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern MICROS_UTC =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z");
  private static final DateTimeFormatter MICROS_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "requestBytesBase64",
          "accountProtectionId",
          "accountProtectionFence",
          "acquiredAt",
          "originalSourceCaptureReferenceBytesBase64",
          "originalSourceCaptureReferenceSha256",
          "sourceEvidenceVector");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountStartSessionActivationProtectionRequest request;
  private final UUID accountProtectionId;
  private final long accountProtectionFence;
  private final Instant acquiredAt;
  private final byte[] originalSourceCaptureReferenceBytes;
  private final String originalSourceCaptureReferenceSha256;
  private final List<SourceEvidence> sourceEvidenceVector;
  private final byte[] canonicalBytes;
  private final byte[] operationIdentityBytes;

  private AccountStartSessionActivationProtectionEvidence(
      AccountStartSessionActivationProtectionRequest request,
      UUID accountProtectionId,
      long accountProtectionFence,
      Instant acquiredAt,
      byte[] originalSourceCaptureReferenceBytes,
      String originalSourceCaptureReferenceSha256,
      List<SourceEvidence> sourceEvidenceVector,
      byte[] canonicalBytes,
      byte[] operationIdentityBytes) {
    this.request = request;
    this.accountProtectionId = accountProtectionId;
    this.accountProtectionFence = accountProtectionFence;
    this.acquiredAt = acquiredAt;
    this.originalSourceCaptureReferenceBytes = originalSourceCaptureReferenceBytes.clone();
    this.originalSourceCaptureReferenceSha256 = originalSourceCaptureReferenceSha256;
    this.sourceEvidenceVector = List.copyOf(sourceEvidenceVector);
    this.canonicalBytes = canonicalBytes.clone();
    this.operationIdentityBytes = operationIdentityBytes.clone();
  }

  /** Creates canonical evidence while retaining the exact Account capture and complete sources. */
  public static AccountStartSessionActivationProtectionEvidence create(
      AccountStartSessionActivationProtectionRequest request,
      UUID accountProtectionId,
      long accountProtectionFence,
      Instant acquiredAt,
      byte[] exactOriginalSourceCaptureReferenceBytes,
      String originalSourceCaptureReferenceSha256,
      List<SourceEvidence> sourceEvidenceVector) {
    Objects.requireNonNull(request, "request is required");
    requireNonNil(accountProtectionId, "accountProtectionId");
    requirePositive(accountProtectionFence, "accountProtectionFence");
    Objects.requireNonNull(acquiredAt, "acquiredAt is required");
    String acquiredAtText = canonicalAcquiredAt(acquiredAt);
    if (!acquiredAt.isBefore(request.originalLeaseExpiresAt())) {
      throw invalid("Account protection must be acquired before the original lease expires");
    }

    byte[] captureReferenceBytes =
        bounded(
            exactOriginalSourceCaptureReferenceBytes,
            AccountStartSessionAdmissionProtectionEvidence.MAX_CAPTURE_REFERENCE_BYTES,
            "original Account source capture reference");
    String captureReferenceDigest = requireDigest(originalSourceCaptureReferenceSha256);
    if (!captureReferenceDigest.equals(sha256(captureReferenceBytes))) {
      throw invalid("Original Account source capture reference digest differs from its bytes");
    }
    AccountStartSessionAdmissionProtectionEvidence.requireCaptureReferenceBinding(
        captureReferenceBytes, request.originalTuple());

    List<SourceEvidence> sources =
        AccountStartSessionAdmissionProtectionEvidence.canonicalSourceVector(sourceEvidenceVector);
    byte[] canonical =
        encode(
            request.canonicalBytes(),
            accountProtectionId,
            accountProtectionFence,
            acquiredAtText,
            captureReferenceBytes,
            captureReferenceDigest,
            sources);
    byte[] operationIdentity =
        encodeOperationIdentity(
            request.operationIdentityBytes(),
            accountProtectionId,
            accountProtectionFence,
            acquiredAtText,
            captureReferenceBytes,
            captureReferenceDigest,
            sources);
    return new AccountStartSessionActivationProtectionEvidence(
        request,
        accountProtectionId,
        accountProtectionFence,
        acquiredAt,
        captureReferenceBytes,
        captureReferenceDigest,
        sources,
        canonical,
        operationIdentity);
  }

  /** Strictly decodes one whole canonical bounded evidence value; decoding grants no authority. */
  public static AccountStartSessionActivationProtectionEvidence decode(byte[] exactCanonicalBytes) {
    byte[] stored =
        bounded(exactCanonicalBytes, MAX_CANONICAL_BYTES, "activation protection evidence");
    try {
      String json = strictUtf8(stored);
      requireCanonicalJson(stored, json, "activation protection evidence");
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root, ROOT_FIELDS, "activation protection evidence");
      if (!SCHEMA.equals(string(root.get("schema"), "schema"))) {
        throw invalid("Unsupported Account activation protection evidence schema");
      }
      Object rawSources = root.get("sourceEvidenceVector");
      if (!(rawSources instanceof List<?> encodedSources)) {
        throw invalid("sourceEvidenceVector must be an array");
      }
      if (encodedSources.isEmpty()
          || encodedSources.size()
              > AccountStartSessionAdmissionProtectionEvidence.MAX_SOURCE_EVIDENCE_COUNT) {
        throw invalid("sourceEvidenceVector is empty or exceeds its item limit");
      }
      List<SourceEvidence> sources = new ArrayList<>(encodedSources.size());
      for (int index = 0; index < encodedSources.size(); index++) {
        sources.add(
            SourceEvidence.fromStored(
                decodeBase64(encodedSources.get(index), "sourceEvidenceVector[" + index + "]")));
      }
      AccountStartSessionActivationProtectionEvidence result =
          create(
              AccountStartSessionActivationProtectionRequest.decode(
                  decodeBase64(root.get("requestBytesBase64"), "requestBytesBase64")),
              canonicalUuid(root.get("accountProtectionId"), "accountProtectionId"),
              positiveLong(root.get("accountProtectionFence"), "accountProtectionFence"),
              parseAcquiredAt(string(root.get("acquiredAt"), "acquiredAt")),
              decodeBase64(
                  root.get("originalSourceCaptureReferenceBytesBase64"),
                  "originalSourceCaptureReferenceBytesBase64"),
              string(
                  root.get("originalSourceCaptureReferenceSha256"),
                  "originalSourceCaptureReferenceSha256"),
              sources);
      if (!MessageDigest.isEqual(stored, result.canonicalBytes)) {
        throw invalid("Account activation protection evidence members are inconsistent");
      }
      return result;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Account activation protection evidence is malformed");
    }
  }

  public AccountStartSessionActivationProtectionRequest request() {
    return request;
  }

  public UUID accountProtectionId() {
    return accountProtectionId;
  }

  public long accountProtectionFence() {
    return accountProtectionFence;
  }

  public Instant acquiredAt() {
    return acquiredAt;
  }

  public byte[] originalSourceCaptureReferenceBytes() {
    return originalSourceCaptureReferenceBytes.clone();
  }

  public String originalSourceCaptureReferenceSha256() {
    return originalSourceCaptureReferenceSha256;
  }

  public List<SourceEvidence> sourceEvidenceVector() {
    return List.copyOf(sourceEvidenceVector);
  }

  /** Full exact storage bytes, retaining both transient owner-read correlation UUIDs. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /**
   * Stable retry identity retains source/protection values and normalizes only owner read UUIDs.
   */
  public byte[] operationIdentityBytes() {
    return operationIdentityBytes.clone();
  }

  public String requestDigest() {
    return "sha256:" + sha256(operationIdentityBytes);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof AccountStartSessionActivationProtectionEvidence that
            && Arrays.equals(canonicalBytes, that.canonicalBytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes);
  }

  private static byte[] encode(
      byte[] requestBytes,
      UUID protectionId,
      long protectionFence,
      String acquiredAt,
      byte[] captureReferenceBytes,
      String captureReferenceDigest,
      List<SourceEvidence> sources) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", SCHEMA);
    value.put("requestBytesBase64", Base64.getEncoder().encodeToString(requestBytes));
    value.put("accountProtectionId", protectionId.toString());
    value.put("accountProtectionFence", Long.toString(protectionFence));
    value.put("acquiredAt", acquiredAt);
    value.put(
        "originalSourceCaptureReferenceBytesBase64",
        Base64.getEncoder().encodeToString(captureReferenceBytes));
    value.put("originalSourceCaptureReferenceSha256", captureReferenceDigest);
    value.put(
        "sourceEvidenceVector",
        sources.stream()
            .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
            .toList());
    return canonicalJson(value, MAX_CANONICAL_BYTES, "Account activation protection evidence");
  }

  private static byte[] encodeOperationIdentity(
      byte[] requestOperationIdentityBytes,
      UUID protectionId,
      long protectionFence,
      String acquiredAt,
      byte[] captureReferenceBytes,
      String captureReferenceDigest,
      List<SourceEvidence> sources) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", OPERATION_IDENTITY_SCHEMA);
    value.put(
        "requestOperationIdentityBytesBase64",
        Base64.getEncoder().encodeToString(requestOperationIdentityBytes));
    value.put("accountProtectionId", protectionId.toString());
    value.put("accountProtectionFence", Long.toString(protectionFence));
    value.put("acquiredAt", acquiredAt);
    value.put(
        "originalSourceCaptureReferenceBytesBase64",
        Base64.getEncoder().encodeToString(captureReferenceBytes));
    value.put("originalSourceCaptureReferenceSha256", captureReferenceDigest);
    value.put(
        "sourceEvidenceVector",
        sources.stream()
            .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
            .toList());
    return canonicalJson(
        value, MAX_CANONICAL_BYTES, "Account activation evidence operation identity");
  }

  private static byte[] canonicalJson(Map<String, Object> value, int maximum, String label) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (canonical.length > maximum) throw invalid(label + " exceeds its byte limit");
      return canonical;
    } catch (IOException impossible) {
      throw new IllegalStateException(label + " cannot be encoded", impossible);
    }
  }

  private static byte[] decodeBase64(Object value, String field) {
    String text = string(value, field);
    try {
      byte[] decoded = Base64.getDecoder().decode(text);
      if (!Base64.getEncoder().encodeToString(decoded).equals(text)) {
        throw invalid(field + " must use canonical Base64");
      }
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is malformed Base64");
    }
  }

  private static byte[] bounded(byte[] value, int maximum, String name) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw invalid(name + " is absent or exceeds its byte limit");
    }
    return value.clone();
  }

  private static String canonicalAcquiredAt(Instant value) {
    if (value.getNano() % 1_000 != 0) {
      throw invalid("acquiredAt must use exact UTC microsecond precision");
    }
    String text = MICROS_FORMAT.format(value);
    if (!MICROS_UTC.matcher(text).matches()) {
      throw invalid("acquiredAt must use canonical UTC microsecond text");
    }
    return text;
  }

  private static Instant parseAcquiredAt(String value) {
    if (!MICROS_UTC.matcher(value).matches()) {
      throw invalid("acquiredAt must use canonical UTC microsecond text");
    }
    try {
      Instant parsed = Instant.parse(value);
      if (!MICROS_FORMAT.format(parsed).equals(value)) {
        throw invalid("acquiredAt must use canonical UTC microsecond text");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException expected) throw expected;
      throw invalid("acquiredAt is malformed");
    }
  }

  private static String requireDigest(String value) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw invalid("originalSourceCaptureReferenceSha256 must be lowercase SHA-256 hex");
    }
    return value;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static long positiveLong(Object value, String field) {
    String text = string(value, field);
    if (!POSITIVE_LONG.matcher(text).matches()) {
      throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
    }
    try {
      long parsed = Long.parseLong(text);
      if (parsed <= 0L || !Long.toString(parsed).equals(text)) {
        throw invalid(field + " must be a positive canonical signed 64-bit decimal string");
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalid(field + " is outside the supported signed 64-bit range");
    }
  }

  private static UUID canonicalUuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || NIL_UUID.equals(parsed)) {
        throw invalid(field + " must be a canonical lowercase non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical lowercase non-nil UUID");
    }
  }

  private static UUID requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (NIL_UUID.equals(value)) throw invalid(field + " must be a non-nil UUID");
    return value;
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) throw invalid(field + " must be positive");
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text)) throw invalid(field + " must be a string");
    return text;
  }

  private static void requireExactFields(
      Map<String, Object> value, Set<String> expected, String label) {
    if (value == null || !value.keySet().equals(expected)) {
      throw invalid(label + " has missing or unsupported fields");
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
      throw invalid("Account activation protection evidence is not valid UTF-8");
    }
  }

  private static void requireCanonicalJson(byte[] originalBytes, String json, String label)
      throws IOException {
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
    if (!MessageDigest.isEqual(canonical, originalBytes)) {
      throw invalid(label + " is not canonical RFC 8785 JSON");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
