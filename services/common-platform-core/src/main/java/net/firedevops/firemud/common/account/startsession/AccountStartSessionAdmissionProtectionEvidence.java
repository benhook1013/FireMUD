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
import java.util.Base64;
import java.util.Comparator;
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
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical integrity evidence for one Account StartSession admission-protection value.
 *
 * <p>This carrier retains the exact request, Account protection identity, capture reference, and
 * complete ordered Account source-evidence children. It proves supplied-byte integrity and
 * structural binding only; it does not authenticate the Account issuer, establish source
 * currentness, prove a live Game Session claim, or itself exclude source writers.
 */
public final class AccountStartSessionAdmissionProtectionEvidence {
  public static final String SCHEMA = "account-start-session-admission-protection-evidence/v1";
  public static final String SOURCE_CAPTURE_REFERENCE_SCHEMA =
      "account-start-session-authority-capture/v1";
  public static final int MAX_CANONICAL_BYTES = 8 * 1024 * 1024;
  public static final int MAX_CAPTURE_REFERENCE_BYTES = 256 * 1024;
  public static final int MAX_SOURCE_EVIDENCE_COUNT = 256;
  public static final int MAX_SOURCE_EVIDENCE_VECTOR_BYTES = 4 * 1024 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern MILLIS_UTC =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
  private static final DateTimeFormatter MILLIS_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "schema",
          "requestBytesBase64",
          "accountProtectionId",
          "accountProtectionFence",
          "originalSourceCaptureReferenceBytesBase64",
          "originalSourceCaptureReferenceSha256",
          "sourceEvidenceVector");
  private static final Set<String> CAPTURE_REFERENCE_FIELDS =
      Set.of("schema", "controlPlaneRequestId", "capturedAt", "bundleReference", "snapshotSha256");
  private static final Set<String> BUNDLE_REFERENCE_FIELDS =
      Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountStartSessionAdmissionProtectionRequest request;
  private final UUID accountProtectionId;
  private final long accountProtectionFence;
  private final byte[] originalSourceCaptureReferenceBytes;
  private final String originalSourceCaptureReferenceSha256;
  private final List<SourceEvidence> sourceEvidenceVector;
  private final byte[] canonicalBytes;

  private AccountStartSessionAdmissionProtectionEvidence(
      AccountStartSessionAdmissionProtectionRequest request,
      UUID accountProtectionId,
      long accountProtectionFence,
      byte[] originalSourceCaptureReferenceBytes,
      String originalSourceCaptureReferenceSha256,
      List<SourceEvidence> sourceEvidenceVector,
      byte[] canonicalBytes) {
    this.request = request;
    this.accountProtectionId = accountProtectionId;
    this.accountProtectionFence = accountProtectionFence;
    this.originalSourceCaptureReferenceBytes = originalSourceCaptureReferenceBytes.clone();
    this.originalSourceCaptureReferenceSha256 = originalSourceCaptureReferenceSha256;
    this.sourceEvidenceVector = List.copyOf(sourceEvidenceVector);
    this.canonicalBytes = canonicalBytes.clone();
  }

  /** Creates one canonical Account evidence value from exact capture and source bytes. */
  public static AccountStartSessionAdmissionProtectionEvidence create(
      AccountStartSessionAdmissionProtectionRequest request,
      UUID accountProtectionId,
      long accountProtectionFence,
      byte[] exactOriginalSourceCaptureReferenceBytes,
      String originalSourceCaptureReferenceSha256,
      List<SourceEvidence> sourceEvidenceVector) {
    Objects.requireNonNull(request, "request is required");
    requireNonNil(accountProtectionId, "accountProtectionId");
    requirePositive(accountProtectionFence, "accountProtectionFence");

    byte[] captureReferenceBytes =
        bounded(
            exactOriginalSourceCaptureReferenceBytes,
            MAX_CAPTURE_REFERENCE_BYTES,
            "original Account source capture reference");
    String captureReferenceDigest = requireDigest(originalSourceCaptureReferenceSha256);
    if (!captureReferenceDigest.equals(sha256(captureReferenceBytes))) {
      throw invalid("Original Account source capture reference digest differs from its bytes");
    }
    requireCaptureReferenceBinding(captureReferenceBytes, request.originalTuple());

    List<SourceEvidence> sources = canonicalSourceVector(sourceEvidenceVector);
    byte[] canonical =
        encode(
            request.canonicalBytes(),
            accountProtectionId,
            accountProtectionFence,
            captureReferenceBytes,
            captureReferenceDigest,
            sources);
    return new AccountStartSessionAdmissionProtectionEvidence(
        request,
        accountProtectionId,
        accountProtectionFence,
        captureReferenceBytes,
        captureReferenceDigest,
        sources,
        canonical);
  }

  /** Strictly decodes one whole canonical evidence value; decoding grants no issuer authority. */
  public static AccountStartSessionAdmissionProtectionEvidence decode(byte[] exactCanonicalBytes) {
    byte[] stored =
        bounded(exactCanonicalBytes, MAX_CANONICAL_BYTES, "admission protection evidence");
    try {
      String json = strictUtf8(stored);
      requireCanonicalJson(stored, json, "admission protection evidence");
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root, ROOT_FIELDS, "admission protection evidence");
      if (!SCHEMA.equals(string(root.get("schema"), "schema"))) {
        throw invalid("Unsupported Account admission protection evidence schema");
      }
      Object rawSources = root.get("sourceEvidenceVector");
      if (!(rawSources instanceof List<?> encodedSources)) {
        throw invalid("sourceEvidenceVector must be an array");
      }
      if (encodedSources.isEmpty() || encodedSources.size() > MAX_SOURCE_EVIDENCE_COUNT) {
        throw invalid("sourceEvidenceVector is empty or exceeds its item limit");
      }
      List<SourceEvidence> sources = new ArrayList<>(encodedSources.size());
      for (int index = 0; index < encodedSources.size(); index++) {
        String field = "sourceEvidenceVector[" + index + "]";
        byte[] sourceBytes = decodeBase64(encodedSources.get(index), field);
        sources.add(SourceEvidence.fromStored(sourceBytes));
      }
      AccountStartSessionAdmissionProtectionEvidence result =
          create(
              AccountStartSessionAdmissionProtectionRequest.decode(
                  decodeBase64(root.get("requestBytesBase64"), "requestBytesBase64")),
              canonicalUuid(root.get("accountProtectionId"), "accountProtectionId"),
              positiveLong(root.get("accountProtectionFence"), "accountProtectionFence"),
              decodeBase64(
                  root.get("originalSourceCaptureReferenceBytesBase64"),
                  "originalSourceCaptureReferenceBytesBase64"),
              string(
                  root.get("originalSourceCaptureReferenceSha256"),
                  "originalSourceCaptureReferenceSha256"),
              sources);
      if (!MessageDigest.isEqual(stored, result.canonicalBytes)) {
        throw invalid("Account admission protection evidence members are inconsistent");
      }
      return result;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Account admission protection evidence is malformed");
    }
  }

  public AccountStartSessionAdmissionProtectionRequest request() {
    return request;
  }

  public UUID accountProtectionId() {
    return accountProtectionId;
  }

  public long accountProtectionFence() {
    return accountProtectionFence;
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

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  private static byte[] encode(
      byte[] requestBytes,
      UUID protectionId,
      long protectionFence,
      byte[] captureReferenceBytes,
      String captureReferenceDigest,
      List<SourceEvidence> sources) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", SCHEMA);
    root.put("requestBytesBase64", Base64.getEncoder().encodeToString(requestBytes));
    root.put("accountProtectionId", protectionId.toString());
    root.put("accountProtectionFence", Long.toString(protectionFence));
    root.put(
        "originalSourceCaptureReferenceBytesBase64",
        Base64.getEncoder().encodeToString(captureReferenceBytes));
    root.put("originalSourceCaptureReferenceSha256", captureReferenceDigest);
    root.put(
        "sourceEvidenceVector",
        sources.stream()
            .map(source -> Base64.getEncoder().encodeToString(source.canonicalBytes()))
            .toList());
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (canonical.length > MAX_CANONICAL_BYTES) {
        throw invalid("Account admission protection evidence exceeds its byte limit");
      }
      return canonical;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Account admission protection evidence cannot be encoded", impossible);
    }
  }

  private static List<SourceEvidence> canonicalSourceVector(
      List<SourceEvidence> sourceEvidenceVector) {
    Objects.requireNonNull(sourceEvidenceVector, "sourceEvidenceVector is required");
    if (sourceEvidenceVector.isEmpty() || sourceEvidenceVector.size() > MAX_SOURCE_EVIDENCE_COUNT) {
      throw invalid("sourceEvidenceVector is empty or exceeds its item limit");
    }
    List<SourceEvidence> sources =
        sourceEvidenceVector.stream()
            .map(
                source -> {
                  Objects.requireNonNull(source, "sourceEvidenceVector child is required");
                  byte[] exactBytes = source.canonicalBytes();
                  return SourceEvidence.fromStored(exactBytes);
                })
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (sources.stream().map(SourceEvidence::key).distinct().count() != sources.size()) {
      throw invalid("sourceEvidenceVector contains duplicate source keys");
    }
    long totalBytes = 0L;
    for (SourceEvidence source : sources) {
      totalBytes += source.canonicalBytes().length;
      if (totalBytes > MAX_SOURCE_EVIDENCE_VECTOR_BYTES) {
        throw invalid("sourceEvidenceVector exceeds its byte limit");
      }
    }
    return List.copyOf(sources);
  }

  private static void requireCaptureReferenceBinding(
      byte[] exactReferenceBytes, StartSessionPostAuthorizationExecutionTuple tuple) {
    try {
      String json = strictUtf8(exactReferenceBytes);
      requireCanonicalJson(exactReferenceBytes, json, "original Account source capture reference");
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root, CAPTURE_REFERENCE_FIELDS, "Account source capture reference");
      if (!SOURCE_CAPTURE_REFERENCE_SCHEMA.equals(string(root.get("schema"), "schema"))) {
        throw invalid("Unsupported Account source capture reference schema");
      }
      if (!tuple
          .controlPlaneRequestId()
          .equals(string(root.get("controlPlaneRequestId"), "controlPlaneRequestId"))) {
        throw invalid("Account source capture reference belongs to another original request");
      }
      String capturedAt = string(root.get("capturedAt"), "capturedAt");
      if (!MILLIS_UTC.matcher(capturedAt).matches()) {
        throw invalid("Account source capture timestamp must use canonical UTC milliseconds");
      }
      Instant parsed = Instant.parse(capturedAt);
      if (!MILLIS_FORMAT.format(parsed).equals(capturedAt)) {
        throw invalid("Account source capture timestamp must use canonical UTC milliseconds");
      }
      if (!SHA256.matcher(string(root.get("snapshotSha256"), "snapshotSha256")).matches()) {
        throw invalid("Account source capture snapshot digest is malformed");
      }
      Map<String, Object> reference = object(root.get("bundleReference"), "bundleReference");
      requireExactFields(reference, BUNDLE_REFERENCE_FIELDS, "Account bundle reference");
      StartSessionAuthorityEvidenceBundle.BundleReference decodedReference =
          new StartSessionAuthorityEvidenceBundle.BundleReference(
              string(reference.get("bundleVersion"), "bundleVersion"),
              string(reference.get("sourceVersion"), "sourceVersion"),
              string(reference.get("sourceFence"), "sourceFence"),
              string(reference.get("linearization"), "linearization"));
      if (!tuple.bundleReference().equals(decodedReference)) {
        throw invalid("Account source capture reference differs from the original tuple bundle");
      }
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("Original Account source capture reference is malformed");
    }
  }

  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) throw invalid(field + " must be an object");
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) throw invalid(field + " has a non-string key");
      result.put(key, entry.getValue());
    }
    return result;
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

  private static String requireDigest(String value) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw invalid("originalSourceCaptureReferenceSha256 must be lowercase SHA-256 hex");
    }
    return value;
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
      Map<String, Object> root, Set<String> expected, String label) {
    if (root == null || !root.keySet().equals(expected)) {
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
      throw invalid("Account admission protection evidence is not valid UTF-8");
    }
  }

  private static void requireCanonicalJson(byte[] originalBytes, String json, String label)
      throws IOException {
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
    if (!MessageDigest.isEqual(canonical, originalBytes)) {
      throw invalid(label + " is not canonical RFC 8785 JSON");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
