package net.firedevops.firemud.common.operator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable, integrity-only canonical post-authorization identity for human StartSession.
 *
 * <p>This value binds the complete pre-authorization tuple to Account's exact authorization
 * projection and the original reservation claim. It does not authenticate its caller, make an
 * Account redemption, or authorize a Game Session mutation.
 */
public final class StartSessionPostAuthorizationExecutionTuple {
  public static final String TUPLE_SCHEMA_ID = "postAuthorizationExecutionTuple";
  public static final String TUPLE_SCHEMA_VERSION = "1";
  public static final int MAX_CANONICAL_TUPLE_BYTES = 256 * 1_024;

  private static final Pattern AUTHORIZATION_REFERENCE_FINGERPRINT =
      Pattern.compile("arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}");
  private static final Set<String> ROOT_FIELDS =
      Set.of(
          "tupleSchemaId",
          "tupleSchemaVersion",
          "preAuthorizationReservationTuple",
          "controlPlaneRequestId",
          "mutationDigest",
          "issuanceKind",
          "authorizationReferenceFingerprint",
          "authenticatedWorkloadIdentity",
          "actionFamilyRequestIdentity",
          "actor",
          "automationPolicy",
          "actionFamily",
          "actionFamilySchemaId",
          "actionFamilySchemaVersion",
          "scope",
          "target",
          "targetOwner",
          "expectedVersion",
          "mutation",
          "auditReason",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence",
          "reservationOwnerId",
          "reservationClaimFence",
          "authorityEvidenceBundle",
          "authorityEvidenceBundleReference");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final StartSessionPreAuthorizationReservationTuple preAuthorizationTuple;
  private final String authenticatedWorkloadIdentity;
  private final String authorizationReferenceFingerprint;
  private final UUID reservationOwnerId;
  private final long reservationClaimFence;
  private final StartSessionAuthorityEvidenceBundle authorityEvidenceBundle;
  private final StartSessionAuthorityEvidenceBundle.BundleReference bundleReference;
  private final byte[] canonicalBytes;

  private StartSessionPostAuthorizationExecutionTuple(
      StartSessionPreAuthorizationReservationTuple preAuthorizationTuple,
      String authenticatedWorkloadIdentity,
      String authorizationReferenceFingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      StartSessionAuthorityEvidenceBundle authorityEvidenceBundle,
      StartSessionAuthorityEvidenceBundle.BundleReference bundleReference,
      byte[] canonicalBytes) {
    this.preAuthorizationTuple = preAuthorizationTuple;
    this.authenticatedWorkloadIdentity = authenticatedWorkloadIdentity;
    this.authorizationReferenceFingerprint = authorizationReferenceFingerprint;
    this.reservationOwnerId = reservationOwnerId;
    this.reservationClaimFence = reservationClaimFence;
    this.authorityEvidenceBundle = authorityEvidenceBundle;
    this.bundleReference = bundleReference;
    this.canonicalBytes = canonicalBytes.clone();
  }

  /**
   * Creates the full human StartSession tuple from the exact pre-tuple and Account-returned
   * evidence. The workload URI must be the same-namespace Logging &amp; Admin mTLS identity.
   */
  public static StartSessionPostAuthorizationExecutionTuple createHuman(
      StartSessionPreAuthorizationReservationTuple preAuthorizationTuple,
      String authenticatedWorkloadIdentity,
      String authorizationReferenceFingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      byte[] exactCanonicalAuthorityEvidenceBundle,
      StartSessionAuthorityEvidenceBundle.BundleReference bundleReference) {
    Objects.requireNonNull(preAuthorizationTuple, "pre-authorization tuple is required");
    Objects.requireNonNull(bundleReference, "original Account bundle reference is required");
    requireLoggingWorkload(authenticatedWorkloadIdentity, preAuthorizationTuple);
    requireFingerprint(authorizationReferenceFingerprint);
    requireNonNil(reservationOwnerId, "reservationOwnerId");
    if (reservationClaimFence <= 0L) {
      throw new IllegalArgumentException("reservationClaimFence must be positive");
    }

    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(exactCanonicalAuthorityEvidenceBundle);
    bundle.requireTupleBinding(preAuthorizationTuple);
    bundle.requireReferenceBinding(bundleReference);
    byte[] canonical =
        encode(
            preAuthorizationTuple,
            authenticatedWorkloadIdentity,
            authorizationReferenceFingerprint,
            reservationOwnerId,
            reservationClaimFence,
            bundle,
            bundleReference);
    return new StartSessionPostAuthorizationExecutionTuple(
        preAuthorizationTuple,
        authenticatedWorkloadIdentity,
        authorizationReferenceFingerprint,
        reservationOwnerId,
        reservationClaimFence,
        bundle,
        bundleReference,
        canonical);
  }

  /** Strictly decodes one complete canonical tuple; decoding does not establish authority. */
  public static StartSessionPostAuthorizationExecutionTuple decode(byte[] exactCanonicalBytes) {
    if (exactCanonicalBytes == null
        || exactCanonicalBytes.length == 0
        || exactCanonicalBytes.length > MAX_CANONICAL_TUPLE_BYTES) {
      throw invalid("post-authorization tuple is empty or exceeds its byte limit");
    }
    try {
      String json = strictUtf8(exactCanonicalBytes);
      byte[] canonicalInput = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      if (!MessageDigest.isEqual(canonicalInput, exactCanonicalBytes)) {
        throw invalid("post-authorization tuple is not canonical UTF-8 JSON");
      }
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      requireExactFields(root, ROOT_FIELDS, "post-authorization tuple");
      if (!TUPLE_SCHEMA_ID.equals(string(root.get("tupleSchemaId"), "tupleSchemaId"))
          || !TUPLE_SCHEMA_VERSION.equals(
              string(root.get("tupleSchemaVersion"), "tupleSchemaVersion"))) {
        throw invalid("unsupported post-authorization tuple schema");
      }

      StartSessionPreAuthorizationReservationTuple preTuple =
          StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
              canonicalObject(
                  root.get("preAuthorizationReservationTuple"),
                  "preAuthorizationReservationTuple"));
      Map<String, Object> referenceValue =
          object(root.get("authorityEvidenceBundleReference"), "authorityEvidenceBundleReference");
      requireExactFields(
          referenceValue,
          Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization"),
          "authorityEvidenceBundleReference");
      StartSessionAuthorityEvidenceBundle.BundleReference reference =
          new StartSessionAuthorityEvidenceBundle.BundleReference(
              string(referenceValue.get("bundleVersion"), "bundleVersion"),
              string(referenceValue.get("sourceVersion"), "sourceVersion"),
              string(referenceValue.get("sourceFence"), "sourceFence"),
              string(referenceValue.get("linearization"), "linearization"));
      Map<String, Object> workloadValue =
          object(root.get("authenticatedWorkloadIdentity"), "authenticatedWorkloadIdentity");
      requireExactFields(
          workloadValue, Set.of("identityKind", "uri"), "authenticatedWorkloadIdentity");
      if (!"MUTUAL_TLS_SPIFFE".equals(string(workloadValue.get("identityKind"), "identityKind"))) {
        throw invalid("unsupported authenticated workload identity kind");
      }
      byte[] bundleBytes =
          canonicalObject(root.get("authorityEvidenceBundle"), "authorityEvidenceBundle")
              .getBytes(StandardCharsets.UTF_8);
      StartSessionPostAuthorizationExecutionTuple result =
          createHuman(
              preTuple,
              string(workloadValue.get("uri"), "authenticatedWorkloadIdentity.uri"),
              string(
                  root.get("authorizationReferenceFingerprint"),
                  "authorizationReferenceFingerprint"),
              canonicalUuid(root.get("reservationOwnerId"), "reservationOwnerId"),
              positiveLong(root.get("reservationClaimFence"), "reservationClaimFence"),
              bundleBytes,
              reference);
      if (!TUPLE_SCHEMA_ID.equals(string(root.get("tupleSchemaId"), "tupleSchemaId"))
          || !MessageDigest.isEqual(result.canonicalBytes, exactCanonicalBytes)) {
        throw invalid("post-authorization tuple members are inconsistent or non-canonical");
      }
      return result;
    } catch (IllegalArgumentException expected) {
      throw expected;
    } catch (IOException | RuntimeException malformed) {
      throw invalid("post-authorization tuple JSON is invalid");
    }
  }

  public StartSessionPreAuthorizationReservationTuple preAuthorizationTuple() {
    return preAuthorizationTuple;
  }

  public String controlPlaneRequestId() {
    return preAuthorizationTuple.controlPlaneRequestId();
  }

  public String mutationDigest() {
    return preAuthorizationTuple.mutationDigest();
  }

  public String issuanceKind() {
    return "human_operator";
  }

  public String authenticatedWorkloadIdentity() {
    return authenticatedWorkloadIdentity;
  }

  public String authorizationReferenceFingerprint() {
    return authorizationReferenceFingerprint;
  }

  public UUID reservationOwnerId() {
    return reservationOwnerId;
  }

  public long reservationClaimFence() {
    return reservationClaimFence;
  }

  public String issuanceFence() {
    return authorityEvidenceBundle.issuanceFence();
  }

  public Map<String, Object> authorityTuple() {
    return authorityEvidenceBundle.authorityTuple();
  }

  public Map<String, Object> membershipVersion() {
    return authorityEvidenceBundle.membershipVersion();
  }

  public byte[] authorityEvidenceBundleBytes() {
    return authorityEvidenceBundle.canonicalBytes();
  }

  public StartSessionAuthorityEvidenceBundle.BundleReference bundleReference() {
    return bundleReference;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String canonicalJson() {
    return new String(canonicalBytes, StandardCharsets.UTF_8);
  }

  private static byte[] encode(
      StartSessionPreAuthorizationReservationTuple preTuple,
      String workloadIdentity,
      String fingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      StartSessionAuthorityEvidenceBundle bundle,
      StartSessionAuthorityEvidenceBundle.BundleReference bundleReference) {
    Map<String, Object> preValue = readCanonicalObject(preTuple.canonicalJson());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("tupleSchemaId", TUPLE_SCHEMA_ID);
    root.put("tupleSchemaVersion", TUPLE_SCHEMA_VERSION);
    root.put("preAuthorizationReservationTuple", preValue);
    root.put("controlPlaneRequestId", preTuple.controlPlaneRequestId());
    root.put("mutationDigest", preTuple.mutationDigest());
    root.put("issuanceKind", "human_operator");
    root.put("authorizationReferenceFingerprint", fingerprint);
    root.put(
        "authenticatedWorkloadIdentity",
        Map.of("identityKind", "MUTUAL_TLS_SPIFFE", "uri", workloadIdentity));
    root.put("actionFamilyRequestIdentity", preValue.get("actionFamilyRequestIdentity"));
    root.put("actor", preValue.get("actor"));
    root.put("automationPolicy", preValue.get("automationPolicy"));
    root.put("actionFamily", preValue.get("actionFamily"));
    root.put("actionFamilySchemaId", preValue.get("actionFamilySchemaId"));
    root.put("actionFamilySchemaVersion", preValue.get("actionFamilySchemaVersion"));
    root.put("scope", preValue.get("scope"));
    root.put("target", preValue.get("target"));
    root.put("targetOwner", preValue.get("targetOwner"));
    root.put("expectedVersion", preValue.get("expectedVersion"));
    root.put("mutation", preValue.get("mutation"));
    root.put("auditReason", preValue.get("auditReason"));
    root.put("authorityTuple", bundle.authorityTuple());
    root.put("membershipVersion", bundle.membershipVersion());
    root.put("issuanceFence", bundle.issuanceFence());
    root.put("reservationOwnerId", reservationOwnerId.toString());
    root.put("reservationClaimFence", reservationClaimFence);
    root.put("authorityEvidenceBundle", bundle.jsonValue());
    root.put(
        "authorityEvidenceBundleReference",
        Map.of(
            "bundleVersion", bundleReference.bundleVersion(),
            "sourceVersion", bundleReference.sourceVersion(),
            "sourceFence", bundleReference.sourceFence(),
            "linearization", bundleReference.linearization()));
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (bytes.length > MAX_CANONICAL_TUPLE_BYTES) {
        throw invalid("post-authorization tuple exceeds its byte limit");
      }
      return bytes;
    } catch (IOException exception) {
      throw new IllegalStateException("could not encode post-authorization tuple", exception);
    }
  }

  private static void requireLoggingWorkload(
      String workloadIdentity, StartSessionPreAuthorizationReservationTuple preTuple) {
    Objects.requireNonNull(workloadIdentity, "authenticated workload identity is required");
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri(workloadIdentity)
            .orElseThrow(() -> invalid("authenticated workload identity is invalid"));
    if (!"logging-admin-service".equals(peer.service())
        || !peer.namespace().equals(preTuple.action().scope().targetNamespace())) {
      throw invalid("StartSession requires the same-namespace Logging & Admin mTLS workload");
    }
  }

  private static void requireFingerprint(String fingerprint) {
    if (fingerprint == null
        || !AUTHORIZATION_REFERENCE_FINGERPRINT.matcher(fingerprint).matches()) {
      throw invalid("Account authorization-reference fingerprint is malformed");
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (value.equals(new UUID(0L, 0L))) {
      throw invalid(field + " must not be nil");
    }
  }

  private static String canonicalObject(Object value, String field) {
    Map<String, Object> object = object(value, field);
    try {
      return new String(
          Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(object)),
          StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw invalid(field + " cannot be decoded as canonical JSON");
    }
  }

  private static Map<String, Object> readCanonicalObject(String canonicalJson) {
    return JSON.readValue(canonicalJson, new TypeReference<>() {});
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException malformed) {
      throw invalid("post-authorization tuple is not valid UTF-8");
    }
  }

  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw invalid(field + " must be an object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach(
        (key, member) -> {
          if (!(key instanceof String text)) {
            throw invalid(field + " has a non-string key");
          }
          result.put(text, member);
        });
    return result;
  }

  private static void requireExactFields(
      Map<String, Object> object, Set<String> fields, String field) {
    if (!object.keySet().equals(fields)) {
      throw invalid(field + " has missing or unsupported fields");
    }
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text)) {
      throw invalid(field + " must be a string");
    }
    return text;
  }

  private static UUID canonicalUuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.equals(new UUID(0L, 0L))) {
        throw invalid(field + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical non-nil UUID");
    }
  }

  private static long positiveLong(Object value, String field) {
    if (!(value instanceof Number number)) {
      throw invalid(field + " must be a positive integer");
    }
    try {
      long parsed = Long.parseLong(number.toString());
      if (parsed <= 0L || !Long.toString(parsed).equals(number.toString())) {
        throw invalid(field + " must be a positive canonical integer");
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw invalid(field + " must be a positive signed 64-bit integer");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
