package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/**
 * Strict in-memory ORIGINAL_NEW gameplay-session payload template and schema-2 reader.
 *
 * <p>{@link Template} produces only the canonical bytes consumed by the final tenant-local session
 * CAS. Its two unique time placeholders are never a finalized session and must never be persisted
 * as a session record. Only {@link #parseFinalized(byte[], Clock)} accepts a complete schema-2
 * value. This class parses and correlates data; it does not perform the final CAS or prove current
 * Account, Redis-registry, lease, barrier, or gameplay-admission authority.
 */
public final class CanonicalGameplayOriginalNewAuthenticatedSession {
  public static final int SCHEMA_VERSION = 2;
  public static final int MAX_SESSION_BYTES = 1024 * 1024;
  public static final long MAX_CANONICAL_SESSION_DURATION_MILLIS = 300_000L;
  public static final long FINAL_ADMISSION_CLOCK_SKEW_MILLIS = 5_000L;

  private static final String ADMISSION_AT_PLACEHOLDER = "__ORIGINAL_NEW_ADMISSION_AT_MS__";
  private static final String CONTINUITY_PLACEHOLDER = "__ORIGINAL_NEW_CONTINUITY_EXPIRES_AT_MS__";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(64)
                  .maxStringLength(MAX_SESSION_BYTES)
                  .maxNumberLength(16)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final ObjectMapper JSON =
      new ObjectMapper(JSON_FACTORY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> SESSION_FIELDS =
      Set.of(
          "schemaVersion",
          "admissionState",
          "originalCandidate",
          "bindingGeneration",
          "accountIndexState",
          "issuerIndexState",
          "regionBridgeState",
          "admissionAt",
          "authTokenHash",
          "authTokenIssuedAt",
          "authTokenExpiresAt",
          "tokenGeneration",
          "issuanceFence",
          "rebindHandle",
          "continuityBindingExpiresAt",
          "membershipBaseline",
          "authorityTuple",
          "outboxCheckpoints",
          "canonicalSessionDurationMillis");
  private static final Set<String> AUTHENTICATION_FIELDS =
      Set.of(
          "authTokenHash",
          "authTokenIssuedAt",
          "authTokenExpiresAt",
          "tokenGeneration",
          "issuanceFence",
          "rebindHandle",
          "continuityBindingExpiresAt",
          "membershipBaseline",
          "authorityTuple",
          "outboxCheckpoints");

  private CanonicalGameplayOriginalNewAuthenticatedSession() {}

  /**
   * Verifies Account's complete signed result and freezes a non-persistable final-CAS template.
   *
   * <p>The duration must be the canonical, already-frozen session duration. The returned bytes
   * contain the exact candidate and unchanged Account membership, tuple, and checkpoint values.
   */
  public static Template template(
      CanonicalGameplayOriginalNewCandidate originalCandidate,
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential accountCredential,
      CanonicalGameplayOriginalNewBoundCredentialVerifier credentialVerifier,
      long canonicalSessionDurationMillis) {
    Objects.requireNonNull(originalCandidate, "original NEW candidate is required");
    Objects.requireNonNull(accountCredential, "Account credential is required");
    Objects.requireNonNull(credentialVerifier, "bound credential verifier is required");
    requireCanonicalDuration(canonicalSessionDurationMillis);

    byte[] candidateBytes = null;
    try (var verified = credentialVerifier.verify(originalCandidate, accountCredential)) {
      candidateBytes = originalCandidate.canonicalBytes();
      CanonicalGameplayOriginalNewCandidate exactCandidate =
          CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes);
      if (!exactCandidate.exactlyMatches(candidateBytes)) throw invalid();
      byte[] templateBytes = makeTemplate(exactCandidate, verified, canonicalSessionDurationMillis);
      try {
        requireTemplateOnlyHasTimePlaceholders(templateBytes);
        return new Template(
            templateBytes, credentialVerifier.clock(), canonicalSessionDurationMillis);
      } finally {
        wipe(templateBytes);
      }
    } finally {
      wipe(candidateBytes);
    }
  }

  /**
   * Strictly reads a completed schema-2 session without establishing current admission authority.
   */
  public static Finalized parseFinalized(byte[] encoded, Clock clock) {
    Objects.requireNonNull(clock, "clock is required");
    if (encoded == null || encoded.length == 0 || encoded.length > MAX_SESSION_BYTES) {
      throw invalid();
    }
    byte[] handle = null;
    byte[] canonical = null;
    byte[] candidateBytes = null;
    try {
      String json = strictUtf8(encoded);
      JsonNode parsed = JSON.readTree(json);
      if (!(parsed instanceof ObjectNode root)) throw invalid();
      requireExactFields(root, SESSION_FIELDS);
      requireSchemaAndAcknowledgements(root);

      candidateBytes = canonicalNode(root.get("originalCandidate"));
      CanonicalGameplayOriginalNewCandidate candidate =
          CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes);
      AccountGameplayAdmissionLeaseEvidence lease = candidate.leaseEvidence();
      requireBindingAnchors(root, candidate, lease);

      String admissionAt = requiredText(root, "admissionAt");
      String continuityExpires = requiredText(root, "continuityBindingExpiresAt");
      String tokenHash = requiredText(root, "authTokenHash");
      String issuedAt = requiredText(root, "authTokenIssuedAt");
      String expiresAt = requiredText(root, "authTokenExpiresAt");
      String tokenGeneration = requiredText(root, "tokenGeneration");
      String issuanceFence = requiredText(root, "issuanceFence");
      String encodedHandle = requiredText(root, "rebindHandle");
      String duration = requiredText(root, "canonicalSessionDurationMillis");

      if (!SHA256.matcher(tokenHash).matches()) throw invalid();
      long admissionAtMillis = positiveDecimal(admissionAt);
      long continuityExpiresAtMillis = positiveDecimal(continuityExpires);
      long tokenIssuedAtSeconds = positiveDecimal(issuedAt);
      long tokenExpiresAtSeconds = positiveDecimal(expiresAt);
      positiveDecimal(issuanceFence);
      long durationMillis = positiveDecimal(duration);
      requireCanonicalDuration(durationMillis);
      if (!"1".equals(tokenGeneration)) throw invalid();
      if (!issuanceFence.equals(lease.carrier().get("issuanceFence"))) {
        throw invalid();
      }
      if (tokenIssuedAtSeconds >= tokenExpiresAtSeconds
          || tokenExpiresAtSeconds - tokenIssuedAtSeconds > 300L) throw invalid();
      long admissionAtSeconds = admissionAtMillis / 1000L;
      if (tokenIssuedAtSeconds > safeAdd(admissionAtSeconds, 5L)
          || tokenExpiresAtSeconds <= admissionAtSeconds) throw invalid();
      if (new java.math.BigInteger((String) lease.carrier().get("expiresAt"))
              .compareTo(java.math.BigInteger.valueOf(admissionAtMillis))
          <= 0) throw invalid();
      if (continuityExpiresAtMillis != safeAdd(admissionAtMillis, durationMillis)) throw invalid();
      long nowMillis = clock.millis();
      if (nowMillis <= 0L
          || admissionAtMillis > safeAdd(nowMillis, FINAL_ADMISSION_CLOCK_SKEW_MILLIS)) {
        throw invalid();
      }

      handle = decodeHandle(encodedHandle);
      canonical = canonicalNode(root);
      if (!Arrays.equals(canonical, encoded)) throw invalid();
      return new Finalized(
          canonical,
          candidate,
          admissionAtMillis,
          continuityExpiresAtMillis,
          tokenHash,
          tokenIssuedAtSeconds,
          tokenExpiresAtSeconds,
          issuanceFence,
          durationMillis,
          handle);
    } catch (RuntimeException rejected) {
      throw invalid();
    } catch (IOException malformed) {
      throw invalid();
    } finally {
      wipe(handle);
      wipe(canonical);
      wipe(candidateBytes);
    }
  }

  private static byte[] makeTemplate(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayOriginalNewBoundCredentialVerifier.VerifiedBoundCredential credential,
      long canonicalSessionDurationMillis) {
    byte[] handle = null;
    byte[] candidateBytes = null;
    try {
      handle = credential.opaqueRebindHandle();
      candidateBytes = candidate.canonicalBytes();
      ObjectNode root = JSON.createObjectNode();
      root.put("schemaVersion", SCHEMA_VERSION);
      root.put("admissionState", "COMPLETE");
      root.set("originalCandidate", JSON.readTree(candidateBytes));
      root.put("bindingGeneration", candidate.transition().bindingGeneration().toString());
      root.put("accountIndexState", "ACKNOWLEDGED");
      root.put("issuerIndexState", "ACKNOWLEDGED");
      root.put("regionBridgeState", "ACKNOWLEDGED");
      root.put("admissionAt", ADMISSION_AT_PLACEHOLDER);
      root.put("authTokenHash", credential.tokenHash());
      root.put("authTokenIssuedAt", Long.toString(credential.issuedAtEpochSeconds()));
      root.put("authTokenExpiresAt", Long.toString(credential.expiresAtEpochSeconds()));
      root.put("tokenGeneration", credential.tokenGeneration());
      root.put("issuanceFence", credential.issuanceFence());
      root.put("rebindHandle", Base64.getUrlEncoder().withoutPadding().encodeToString(handle));
      root.put("continuityBindingExpiresAt", CONTINUITY_PLACEHOLDER);
      Map<String, Object> lease = candidate.leaseEvidence().carrier();
      root.set("membershipBaseline", JSON.valueToTree(lease.get("membershipBaseline")));
      root.set("authorityTuple", JSON.valueToTree(lease.get("authorityTuple")));
      root.set("outboxCheckpoints", JSON.valueToTree(lease.get("outboxCheckpoints")));
      root.put("canonicalSessionDurationMillis", Long.toString(canonicalSessionDurationMillis));
      return canonicalNode(root);
    } catch (IOException | RuntimeException malformed) {
      throw invalid();
    } finally {
      wipe(handle);
      wipe(candidateBytes);
    }
  }

  private static void requireTemplateOnlyHasTimePlaceholders(byte[] templateBytes) {
    try {
      ObjectNode root = (ObjectNode) JSON.readTree(strictUtf8(templateBytes));
      requireExactFields(root, SESSION_FIELDS);
      if (!ADMISSION_AT_PLACEHOLDER.equals(requiredText(root, "admissionAt"))
          || !CONTINUITY_PLACEHOLDER.equals(requiredText(root, "continuityBindingExpiresAt")))
        throw invalid();
      root.fieldNames()
          .forEachRemaining(
              name -> {
                if (!name.equals("admissionAt") && !name.equals("continuityBindingExpiresAt")) {
                  JsonNode value = root.get(name);
                  if (containsPlaceholder(value)) throw invalid();
                }
              });
    } catch (IOException | RuntimeException malformed) {
      throw invalid();
    }
  }

  private static boolean containsPlaceholder(JsonNode value) {
    if (value.isTextual()) {
      return ADMISSION_AT_PLACEHOLDER.equals(value.textValue())
          || CONTINUITY_PLACEHOLDER.equals(value.textValue());
    }
    if (value.isObject()) {
      var fields = value.fields();
      while (fields.hasNext()) if (containsPlaceholder(fields.next().getValue())) return true;
    } else if (value.isArray()) {
      for (JsonNode item : value) if (containsPlaceholder(item)) return true;
    }
    return false;
  }

  private static void requireSchemaAndAcknowledgements(ObjectNode root) {
    JsonNode schema = root.get("schemaVersion");
    if (schema == null
        || !schema.isIntegralNumber()
        || !schema.canConvertToInt()
        || schema.intValue() != SCHEMA_VERSION) {
      throw invalid();
    }
    if (!"COMPLETE".equals(requiredText(root, "admissionState"))
        || !"ACKNOWLEDGED".equals(requiredText(root, "accountIndexState"))
        || !"ACKNOWLEDGED".equals(requiredText(root, "issuerIndexState"))
        || !"ACKNOWLEDGED".equals(requiredText(root, "regionBridgeState"))) throw invalid();
    for (String field : AUTHENTICATION_FIELDS) {
      JsonNode value = root.get(field);
      if (value == null || value.isNull()) throw invalid();
    }
  }

  private static void requireBindingAnchors(
      ObjectNode root,
      CanonicalGameplayOriginalNewCandidate candidate,
      AccountGameplayAdmissionLeaseEvidence lease)
      throws IOException {
    if (!candidate
        .transition()
        .bindingGeneration()
        .toString()
        .equals(requiredText(root, "bindingGeneration"))) throw invalid();
    Map<String, Object> carrier = lease.carrier();
    requireCanonicalEqual(root.get("membershipBaseline"), carrier.get("membershipBaseline"));
    requireCanonicalEqual(root.get("authorityTuple"), carrier.get("authorityTuple"));
    requireCanonicalEqual(root.get("outboxCheckpoints"), carrier.get("outboxCheckpoints"));
  }

  private static void requireCanonicalEqual(JsonNode node, Object source) throws IOException {
    byte[] left = canonicalNode(node);
    byte[] right = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(source));
    try {
      if (!Arrays.equals(left, right)) throw invalid();
    } finally {
      wipe(left);
      wipe(right);
    }
  }

  private static byte[] decodeHandle(String encoded) {
    if (encoded.isEmpty() || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid();
    try {
      byte[] result = Base64.getUrlDecoder().decode(encoded);
      if (result.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(result).equals(encoded)) {
        wipe(result);
        throw invalid();
      }
      return result;
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static byte[] canonicalNode(JsonNode node) throws IOException {
    if (node == null) throw invalid();
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(node));
    if (canonical.length > MAX_SESSION_BYTES) {
      wipe(canonical);
      throw invalid();
    }
    return canonical;
  }

  private static String strictUtf8(byte[] encoded) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  private static void requireExactFields(ObjectNode object, Set<String> expected) {
    Set<String> actual = new java.util.HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid();
  }

  private static String requiredText(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual() || value.textValue().isEmpty()) throw invalid();
    return value.textValue();
  }

  private static long positiveDecimal(String value) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) throw invalid();
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException malformed) {
      throw invalid();
    }
  }

  private static void requireCanonicalDuration(long value) {
    if (value <= 0L || value > MAX_CANONICAL_SESSION_DURATION_MILLIS) throw invalid();
  }

  private static long safeAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException malformed) {
      throw invalid();
    }
  }

  private static void wipe(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Canonical ORIGINAL_NEW authenticated session is invalid");
  }

  /** In-memory pre-final-CAS bytes. Closing overwrites the encoded opaque handle. */
  public static final class Template implements AutoCloseable {
    private byte[] canonicalTemplateBytes;
    private final Clock clock;
    private final long canonicalSessionDurationMillis;

    private Template(
        byte[] canonicalTemplateBytes, Clock clock, long canonicalSessionDurationMillis) {
      this.canonicalTemplateBytes = canonicalTemplateBytes.clone();
      this.clock = Objects.requireNonNull(clock);
      this.canonicalSessionDurationMillis = canonicalSessionDurationMillis;
    }

    public long canonicalSessionDurationMillis() {
      return canonicalSessionDurationMillis;
    }

    /** Exact canonical preimage for the final CAS; the two time strings are not final values. */
    public byte[] finalCasTemplateBytes() {
      if (canonicalTemplateBytes == null)
        throw new IllegalStateException("Session template is closed");
      return canonicalTemplateBytes.clone();
    }

    /** Validates final-CAS readback and allows only the two server-time values to differ. */
    public Finalized requireFinalizedReadback(byte[] finalCasReadback) {
      if (canonicalTemplateBytes == null)
        throw new IllegalStateException("Session template is closed");
      Finalized finalized = parseFinalized(finalCasReadback, clock);
      byte[] templateCopy = canonicalTemplateBytes.clone();
      byte[] expected = null;
      byte[] readbackBytes = null;
      try {
        ObjectNode root = (ObjectNode) JSON.readTree(strictUtf8(templateCopy));
        root.put("admissionAt", Long.toString(finalized.admissionAtMillis()));
        root.put(
            "continuityBindingExpiresAt",
            Long.toString(finalized.continuityBindingExpiresAtMillis()));
        expected = canonicalNode(root);
        readbackBytes = finalized.canonicalBytes();
        if (!Arrays.equals(expected, readbackBytes)) throw invalid();
        return finalized;
      } catch (RuntimeException rejected) {
        finalized.close();
        throw invalid();
      } catch (Exception malformed) {
        finalized.close();
        throw invalid();
      } finally {
        wipe(templateCopy);
        wipe(expected);
        wipe(readbackBytes);
      }
    }

    @Override
    public void close() {
      wipe(canonicalTemplateBytes);
      canonicalTemplateBytes = null;
    }

    @Override
    public String toString() {
      return "CanonicalGameplayOriginalNewAuthenticatedSession.Template[nonAdmitting,redacted]";
    }
  }

  /** Strictly decoded complete schema-2 bytes; the value itself supplies no current authority. */
  public static final class Finalized implements AutoCloseable {
    private byte[] canonicalBytes;
    private byte[] opaqueRebindHandle;
    private final CanonicalGameplayOriginalNewCandidate originalCandidate;
    private final long admissionAtMillis;
    private final long continuityBindingExpiresAtMillis;
    private final String authTokenHash;
    private final long authTokenIssuedAt;
    private final long authTokenExpiresAt;
    private final String issuanceFence;
    private final long canonicalSessionDurationMillis;

    private Finalized(
        byte[] canonicalBytes,
        CanonicalGameplayOriginalNewCandidate originalCandidate,
        long admissionAtMillis,
        long continuityBindingExpiresAtMillis,
        String authTokenHash,
        long authTokenIssuedAt,
        long authTokenExpiresAt,
        String issuanceFence,
        long canonicalSessionDurationMillis,
        byte[] opaqueRebindHandle) {
      this.canonicalBytes = canonicalBytes.clone();
      this.originalCandidate = originalCandidate;
      this.admissionAtMillis = admissionAtMillis;
      this.continuityBindingExpiresAtMillis = continuityBindingExpiresAtMillis;
      this.authTokenHash = authTokenHash;
      this.authTokenIssuedAt = authTokenIssuedAt;
      this.authTokenExpiresAt = authTokenExpiresAt;
      this.issuanceFence = issuanceFence;
      this.canonicalSessionDurationMillis = canonicalSessionDurationMillis;
      this.opaqueRebindHandle = opaqueRebindHandle.clone();
    }

    public byte[] canonicalBytes() {
      if (canonicalBytes == null) throw new IllegalStateException("Finalized session is closed");
      return canonicalBytes.clone();
    }

    public CanonicalGameplayOriginalNewCandidate originalCandidate() {
      return originalCandidate;
    }

    public CanonicalGameplayOriginalNewCandidate candidate() {
      return originalCandidate;
    }

    public long admissionAtMillis() {
      return admissionAtMillis;
    }

    public long continuityBindingExpiresAtMillis() {
      return continuityBindingExpiresAtMillis;
    }

    public long continuityExpiresAtMillis() {
      return continuityBindingExpiresAtMillis;
    }

    public String authTokenHash() {
      return authTokenHash;
    }

    public long authTokenIssuedAt() {
      return authTokenIssuedAt;
    }

    public long authTokenExpiresAt() {
      return authTokenExpiresAt;
    }

    public String tokenGeneration() {
      return "1";
    }

    public String issuanceFence() {
      return issuanceFence;
    }

    public long canonicalSessionDurationMillis() {
      return canonicalSessionDurationMillis;
    }

    /** Returns only a caller-owned copy; Account remains the sole handle resolver. */
    public byte[] opaqueRebindHandle() {
      if (opaqueRebindHandle == null)
        throw new IllegalStateException("Finalized session is closed");
      return opaqueRebindHandle.clone();
    }

    public byte[] rebindHandle() {
      return opaqueRebindHandle();
    }

    @Override
    public void close() {
      wipe(canonicalBytes);
      wipe(opaqueRebindHandle);
      canonicalBytes = null;
      opaqueRebindHandle = null;
    }

    @Override
    public String toString() {
      return "CanonicalGameplayOriginalNewAuthenticatedSession.Finalized[complete,redacted]";
    }
  }
}
