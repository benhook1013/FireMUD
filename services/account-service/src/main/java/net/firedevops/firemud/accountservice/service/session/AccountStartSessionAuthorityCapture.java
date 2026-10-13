package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable full Account source capture retained before StartSession private cryptography. */
public final class AccountStartSessionAuthorityCapture {
  public static final String SCHEMA = "account-start-session-authority-capture/v1";
  public static final int MAX_CAPTURE_BYTES = 256 * 1024;
  public static final int MAX_SNAPSHOT_BYTES = 240 * 1024;

  private static final Pattern POSITIVE_U64 = Pattern.compile("[1-9][0-9]{0,19}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final DateTimeFormatter CAPTURED_AT_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final String controlPlaneRequestId;
  private final long sourceVersion;
  private final long sourceFence;
  private final String linearization;
  private final String capturedAt;
  private final byte[] snapshotBytes;
  private final String snapshotSha256;
  private final byte[] canonicalBytes;
  private final String canonicalSha256;

  private AccountStartSessionAuthorityCapture(
      String controlPlaneRequestId,
      long sourceVersion,
      long sourceFence,
      String linearization,
      String capturedAt,
      byte[] snapshotBytes,
      String snapshotSha256,
      byte[] canonicalBytes,
      String canonicalSha256) {
    this.controlPlaneRequestId =
        StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
            controlPlaneRequestId);
    this.sourceVersion = positive(sourceVersion, "capture sourceVersion");
    this.sourceFence = positive(sourceFence, "capture sourceFence");
    this.linearization = requireU64(linearization, "capture linearization");
    this.capturedAt = requireCapturedAt(capturedAt);
    this.snapshotBytes = bounded(snapshotBytes, MAX_SNAPSHOT_BYTES, "source snapshot");
    this.snapshotSha256 = requireDigest(snapshotSha256, "source snapshot SHA-256");
    this.canonicalBytes = bounded(canonicalBytes, MAX_CAPTURE_BYTES, "capture reference");
    this.canonicalSha256 = requireDigest(canonicalSha256, "capture SHA-256");
    if (!this.snapshotSha256.equals(sha256(this.snapshotBytes))
        || !this.canonicalSha256.equals(sha256(this.canonicalBytes))) {
      throw invalid("Capture bytes do not match their retained digests");
    }
    requireCanonicalJson(this.snapshotBytes, "source snapshot");
    requireCanonicalJson(this.canonicalBytes, "capture reference");
    var reference = bundleReference();
    requireReferenceEnvelope(
        this.canonicalBytes,
        this.controlPlaneRequestId,
        this.capturedAt,
        reference,
        this.snapshotSha256);
  }

  static AccountStartSessionAuthorityCapture create(
      String controlPlaneRequestId,
      long sourceVersion,
      long sourceFence,
      String linearization,
      String capturedAt,
      byte[] snapshotBytes) {
    String snapshotDigest = sha256(snapshotBytes);
    BundleReference reference =
        new BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            Long.toString(sourceVersion),
            Long.toString(sourceFence),
            linearization);
    byte[] referenceBytes =
        AccountControlUiAuthority.canonical(
            java.util.Map.of(
                "schema", SCHEMA,
                "controlPlaneRequestId", controlPlaneRequestId,
                "capturedAt", capturedAt,
                "bundleReference",
                    java.util.Map.of(
                        "bundleVersion", reference.bundleVersion(),
                        "sourceVersion", reference.sourceVersion(),
                        "sourceFence", reference.sourceFence(),
                        "linearization", reference.linearization()),
                "snapshotSha256", snapshotDigest));
    return new AccountStartSessionAuthorityCapture(
        controlPlaneRequestId,
        sourceVersion,
        sourceFence,
        linearization,
        capturedAt,
        snapshotBytes,
        snapshotDigest,
        referenceBytes,
        sha256(referenceBytes));
  }

  static AccountStartSessionAuthorityCapture fromStorage(
      String controlPlaneRequestId,
      Long sourceVersion,
      Long sourceFence,
      String linearization,
      String capturedAt,
      byte[] snapshotBytes,
      String snapshotSha256,
      byte[] canonicalBytes,
      String canonicalSha256) {
    if (sourceVersion == null || sourceFence == null) {
      throw invalid("Stored capture version or fence is absent");
    }
    return new AccountStartSessionAuthorityCapture(
        controlPlaneRequestId,
        sourceVersion,
        sourceFence,
        linearization,
        capturedAt,
        snapshotBytes,
        snapshotSha256,
        canonicalBytes,
        canonicalSha256);
  }

  public String controlPlaneRequestId() {
    return controlPlaneRequestId;
  }

  public long sourceVersion() {
    return sourceVersion;
  }

  public long sourceFence() {
    return sourceFence;
  }

  public String linearization() {
    return linearization;
  }

  public String capturedAt() {
    return capturedAt;
  }

  public BundleReference bundleReference() {
    return new BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
        Long.toString(sourceVersion),
        Long.toString(sourceFence),
        linearization);
  }

  public byte[] snapshotBytes() {
    return snapshotBytes.clone();
  }

  public String snapshotSha256() {
    return snapshotSha256;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String canonicalSha256() {
    return canonicalSha256;
  }

  boolean sameSnapshot(AccountStartSessionAuthorityCapture other) {
    return other != null
        && controlPlaneRequestId.equals(other.controlPlaneRequestId)
        && MessageDigest.isEqual(snapshotBytes, other.snapshotBytes)
        && snapshotSha256.equals(other.snapshotSha256);
  }

  boolean sameStoredValue(AccountStartSessionAuthorityCapture other) {
    return sameSnapshot(other)
        && sourceVersion == other.sourceVersion
        && sourceFence == other.sourceFence
        && linearization.equals(other.linearization)
        && capturedAt.equals(other.capturedAt)
        && MessageDigest.isEqual(canonicalBytes, other.canonicalBytes)
        && canonicalSha256.equals(other.canonicalSha256);
  }

  private static void requireReferenceEnvelope(
      byte[] bytes,
      String requestId,
      String capturedAt,
      BundleReference reference,
      String snapshotSha256) {
    try {
      String text = new String(bytes, StandardCharsets.UTF_8);
      Object value = JSON.readValue(text, Object.class);
      if (!(value instanceof java.util.Map<?, ?> root)
          || !root.keySet()
              .equals(
                  java.util.Set.of(
                      "schema",
                      "controlPlaneRequestId",
                      "capturedAt",
                      "bundleReference",
                      "snapshotSha256"))
          || !SCHEMA.equals(root.get("schema"))
          || !requestId.equals(root.get("controlPlaneRequestId"))
          || !capturedAt.equals(root.get("capturedAt"))
          || !snapshotSha256.equals(root.get("snapshotSha256"))
          || !(root.get("bundleReference") instanceof java.util.Map<?, ?> ref)
          || !ref.keySet()
              .equals(
                  java.util.Set.of(
                      "bundleVersion", "sourceVersion", "sourceFence", "linearization"))
          || !reference.bundleVersion().equals(ref.get("bundleVersion"))
          || !reference.sourceVersion().equals(ref.get("sourceVersion"))
          || !reference.sourceFence().equals(ref.get("sourceFence"))
          || !reference.linearization().equals(ref.get("linearization"))) {
        throw invalid("Capture reference does not bind the full source snapshot");
      }
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException expected) throw expected;
      throw invalid("Capture reference is malformed");
    }
  }

  private static void requireCanonicalJson(byte[] bytes, String name) {
    try {
      String original = new String(bytes, StandardCharsets.UTF_8);
      if (!Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(original))) {
        throw invalid(name + " is not canonical JSON");
      }
      JSON.readValue(original, Object.class);
    } catch (java.io.IOException | RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException expected) throw expected;
      throw invalid(name + " is malformed");
    }
  }

  private static byte[] bounded(byte[] value, int maximum, String field) {
    Objects.requireNonNull(value, field + " bytes are required");
    if (value.length == 0 || value.length > maximum) {
      throw invalid(field + " bytes are absent or over the supported bound");
    }
    return value.clone();
  }

  private static long positive(long value, String field) {
    if (value <= 0L) throw invalid(field + " must be positive");
    return value;
  }

  private static String requireU64(String value, String field) {
    if (value == null
        || !POSITIVE_U64.matcher(value).matches()
        || new java.math.BigInteger(value)
                .compareTo(
                    java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE))
            > 0) {
      throw invalid(field + " is malformed");
    }
    return value;
  }

  private static String requireDigest(String value, String field) {
    if (value == null || !SHA256.matcher(value).matches()) throw invalid(field + " is malformed");
    return value;
  }

  private static String requireCapturedAt(String value) {
    if (value == null) throw invalid("Database capture timestamp is absent");
    try {
      Instant parsed = Instant.parse(value);
      String canonical = CAPTURED_AT_FORMAT.format(parsed);
      if (!canonical.equals(value) || parsed.getNano() % 1_000_000 != 0) {
        throw invalid("Database capture timestamp must use canonical millisecond precision");
      }
      return canonical;
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException expected) throw expected;
      throw invalid("Database capture timestamp is malformed");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
