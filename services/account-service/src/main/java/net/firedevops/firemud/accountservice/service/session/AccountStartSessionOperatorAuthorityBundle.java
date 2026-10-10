package net.firedevops.firemud.accountservice.service.session;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority.Snapshot;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository.Stored;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Exact ADR 0047 Account authority evidence for the tenantAdmin StartSession branch. */
public final class AccountStartSessionOperatorAuthorityBundle {
  public static final String BUNDLE_VERSION = StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION;
  public static final String HUMAN_EVIDENCE_TYPE =
      StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE;
  private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Set<String> CAPTURE_SNAPSHOT_FIELDS =
      Set.of(
          "schema",
          "controlPlaneRequestId",
          "preAuthorizationTuple",
          "mutationDigest",
          "accountId",
          "tenantId",
          "targetOwner",
          "loggingWorkloadUri",
          "reservationOwnerId",
          "reservationClaimFence",
          "controlUiOperationId",
          "controlUiTokenJti",
          "controlUiTokenHash",
          "controlUiSignerReceipt",
          "controlUiSignerReceiptSha256",
          "sourceVectorEvidence",
          "sourceVector",
          "outboxCheckpoints",
          "authorityTuple",
          "membershipVersion",
          "accountIdentitySource",
          "issuanceFence",
          "issuanceFenceSourceVersion");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final StartSessionAuthorityEvidenceBundle decoded;
  private final byte[] canonicalBytes;
  private final Map<String, Object> value;

  private AccountStartSessionOperatorAuthorityBundle(StartSessionAuthorityEvidenceBundle decoded) {
    this.decoded = Objects.requireNonNull(decoded, "shared authority bundle is required");
    this.canonicalBytes = decoded.canonicalBytes();
    this.value = decoded.jsonValue();
  }

  /**
   * Builds the one supported human branch only from Account's already committed immutable capture
   * and the original control-ui token row proven by {@link AccountControlUiActorService}.
   */
  public static AccountStartSessionOperatorAuthorityBundle create(
      StartSessionPreAuthorizationReservationTuple tuple,
      AccountStartSessionAuthorityCapture capture,
      Stored controlUiIssuance,
      UUID issuanceOperationId,
      Instant issuedAt,
      Instant referenceExpiresAt) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    Objects.requireNonNull(capture, "durable Account authority capture is required");
    Objects.requireNonNull(controlUiIssuance, "original control-ui issuance is required");
    Objects.requireNonNull(issuanceOperationId, "issuance operation identity is required");
    requireTimeWindow(issuedAt, referenceExpiresAt);

    Map<String, Object> snapshot = captureSnapshot(capture);
    UUID actor = canonicalUuid(snapshot.get("accountId"), "captured accountId");
    UUID tenant = canonicalUuid(snapshot.get("tenantId"), "captured tenantId");
    UUID controlUiJti = canonicalUuid(snapshot.get("controlUiTokenJti"), "captured token jti");
    Map<String, Object> authorityTuple = object(snapshot.get("authorityTuple"), "authorityTuple");
    Map<String, Object> membershipVersion =
        object(snapshot.get("membershipVersion"), "membershipVersion");
    Map<String, Object> claims =
        AccountControlUiIssuanceRepository.object(controlUiIssuance.claims);
    requireCapturedTenantClaims(
        tuple, actor, tenant, authorityTuple, membershipVersion, controlUiIssuance, claims);
    if (!tuple.controlPlaneRequestId().equals(snapshot.get("controlPlaneRequestId"))
        || !tuple.targetOwner().equals(snapshot.get("targetOwner"))
        || !tuple.mutationDigest().equals(snapshot.get("mutationDigest"))
        || !Base64.getEncoder()
            .encodeToString(tuple.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .equals(snapshot.get("preAuthorizationTuple"))
        || !controlUiIssuance.operationId.toString().equals(snapshot.get("controlUiOperationId"))
        || !controlUiIssuance.tokenHash.equals(snapshot.get("controlUiTokenHash"))
        || !controlUiIssuance.jti.equals(controlUiJti)) {
      throw denied("Retained Account capture differs from the original request or credential");
    }

    BundleReference reference = referenceForCaptureValue(capture);
    Map<String, Object> typedScope =
        Map.of(
            "tenantId", tuple.action().scope().tenantId().toString(),
            "targetNamespace", tuple.action().scope().targetNamespace());
    Map<String, Object> authorityScope =
        Map.of(
            "scope", typedScope,
            "actionFamily", tuple.actionFamily(),
            "applicableAccountId", actor.toString(),
            "applicableTenantId", tenant.toString());
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId",
                sourceEvidenceId(
                    Base64.getDecoder()
                        .decode(
                            string(snapshot.get("sourceVectorEvidence"), "sourceVectorEvidence")),
                    reference,
                    positiveNumber(snapshot.get("issuanceFence"), "captured issuanceFence"),
                    positiveNumber(
                        snapshot.get("issuanceFenceSourceVersion"),
                        "captured issuanceFenceSourceVersion")),
            "sourceEvidenceVersion", reference.sourceVersion(),
            "projectionStatus", "CURRENT",
            "evaluatedAt", canonicalCaptureTimestamp(capture),
            "expiresAt", referenceExpiresAt.toString());
    Map<String, Object> operationIdentity =
        Map.of(
            "issuanceOperationId", issuanceOperationId.toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> humanEvidence =
        humanTenantEvidence(actor, tenant, authorityTuple, controlUiJti, claims);

    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("bundleVersion", BUNDLE_VERSION);
    bundle.put("authorityScope", authorityScope);
    bundle.put("accountProjectionEvidence", projection);
    bundle.put("issuanceOperationIdentity", operationIdentity);
    bundle.put("issuanceKind", "human_operator");
    bundle.put("authorityTuple", authorityTuple);
    bundle.put("membershipVersion", membershipVersion);
    bundle.put(
        "issuanceFence",
        positiveDecimalString(snapshot.get("issuanceFence"), "captured issuanceFence"));
    bundle.put("issuanceEvidence", humanEvidence);
    AccountStartSessionOperatorAuthorityBundle result =
        decode(AccountControlUiAuthority.canonical(bundle));
    result.requireTupleBinding(tuple);
    result.requireCaptureBinding(capture, tuple, reference);
    return result;
  }

  /** Strictly decodes one closed canonical authorityEvidenceBundle/v1 value. */
  public static AccountStartSessionOperatorAuthorityBundle decode(byte[] exactCanonicalBytes) {
    return new AccountStartSessionOperatorAuthorityBundle(
        StartSessionAuthorityEvidenceBundle.decode(exactCanonicalBytes));
  }

  /** Compares every snapshot-bound authority projection and the exact original source reference. */
  public void requireCurrent(
      Snapshot currentSource,
      Stored currentControlUiIssuance,
      StartSessionPreAuthorizationReservationTuple tuple,
      BundleReference originalReference,
      Instant now) {
    Objects.requireNonNull(currentSource, "current Account source is required");
    Objects.requireNonNull(currentControlUiIssuance, "current control-ui issuance is required");
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    Objects.requireNonNull(originalReference, "original Account source reference is required");
    Objects.requireNonNull(now, "current time is required");
    decoded.requireTupleBinding(tuple);
    decoded.requireReferenceBinding(toSharedReference(originalReference));
    Map<String, Object> projection =
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence");
    if (!now.isBefore(originalReferenceExpiresAt())
        || !now.isBefore(instant(string(projection.get("expiresAt"), "projection expiry")))) {
      throw denied("Original Account authority evidence is expired");
    }
    if (!tuple.actor().accountId().equals(currentSource.actor())
        || !tuple.action().scope().tenantId().equals(currentSource.tenant())
        || !currentControlUiIssuance.accountId.equals(currentSource.actor())
        || !currentControlUiIssuance.tenantId.equals(currentSource.tenant())) {
      throw denied("Current Account actor or tenant changed");
    }
    Map<String, Object> claims =
        AccountControlUiIssuanceRepository.object(currentControlUiIssuance.claims);
    requireCurrentTenantClaims(tuple, currentSource, currentControlUiIssuance, claims);
    if (!canonicalEquals(currentSource.authorityTuple(), value.get("authorityTuple"))
        || !canonicalEquals(currentSource.membershipVersion(), value.get("membershipVersion"))) {
      throw denied("Current Account source, generation, or membership changed");
    }
    requireCurrentSourceEvidence(currentSource, originalReference);
    Map<String, Object> expectedEvidence =
        humanTenantEvidence(currentSource, currentControlUiIssuance.jti, claims);
    if (!canonicalEquals(expectedEvidence, value.get("issuanceEvidence"))) {
      throw denied("Current human authority evidence changed");
    }
  }

  /** Current-state proof for a same-attempt replay after the exact response envelope horizon. */
  public void requireCurrentSnapshotForExactReplay(
      Snapshot currentSource,
      Stored currentControlUiIssuance,
      StartSessionPreAuthorizationReservationTuple tuple,
      BundleReference originalReference) {
    Objects.requireNonNull(currentSource, "current Account source is required");
    Objects.requireNonNull(currentControlUiIssuance, "current control-ui issuance is required");
    Objects.requireNonNull(originalReference, "original Account source reference is required");
    decoded.requireTupleBinding(tuple);
    decoded.requireReferenceBinding(toSharedReference(originalReference));
    if (!tuple.actor().accountId().equals(currentSource.actor())
        || !tuple.action().scope().tenantId().equals(currentSource.tenant())
        || !currentControlUiIssuance.accountId.equals(currentSource.actor())
        || !currentControlUiIssuance.tenantId.equals(currentSource.tenant())) {
      throw denied("Current Account actor or tenant changed");
    }
    Map<String, Object> claims =
        AccountControlUiIssuanceRepository.object(currentControlUiIssuance.claims);
    requireCurrentTenantClaims(tuple, currentSource, currentControlUiIssuance, claims);
    if (!canonicalEquals(currentSource.authorityTuple(), value.get("authorityTuple"))
        || !canonicalEquals(currentSource.membershipVersion(), value.get("membershipVersion"))
        || !currentSourceMatchesEvidence(currentSource, originalReference)
        || !canonicalEquals(
            humanTenantEvidence(currentSource, currentControlUiIssuance.jti, claims),
            value.get("issuanceEvidence"))) {
      throw denied("Current Account source differs from the consumed authorization");
    }
  }

  /** Enforces request tuple equality separately from the authority projection. */
  public void requireTupleBinding(StartSessionPreAuthorizationReservationTuple tuple) {
    decoded.requireTupleBinding(tuple);
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public BundleReference referenceForCapture(AccountStartSessionAuthorityCapture capture) {
    Objects.requireNonNull(capture, "original Account source capture is required");
    BundleReference reference = referenceForCaptureValue(capture);
    requireCaptureBinding(capture, null, reference);
    return reference;
  }

  /** Requires the bundle and immutable source reference to identify the same stored capture. */
  public void requireCaptureBinding(
      AccountStartSessionAuthorityCapture capture,
      StartSessionPreAuthorizationReservationTuple tuple,
      BundleReference expectedReference) {
    Objects.requireNonNull(capture, "original Account source capture is required");
    Objects.requireNonNull(expectedReference, "original Account bundle reference is required");
    Map<String, Object> projection =
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence");
    Map<String, Object> snapshot = captureSnapshot(capture);
    String expectedEvidenceId =
        sourceEvidenceId(
            Base64.getDecoder()
                .decode(string(snapshot.get("sourceVectorEvidence"), "sourceVectorEvidence")),
            expectedReference,
            positiveNumber(snapshot.get("issuanceFence"), "captured issuanceFence"),
            positiveNumber(
                snapshot.get("issuanceFenceSourceVersion"), "captured issuanceFenceSourceVersion"));
    if (!referenceForCaptureValue(capture).equals(expectedReference)
        || !expectedEvidenceId.equals(projection.get("sourceEvidenceId"))
        || !Long.toString(capture.sourceVersion()).equals(projection.get("sourceEvidenceVersion"))
        || !canonicalCaptureTimestamp(capture).equals(projection.get("evaluatedAt"))
        || !capture.controlPlaneRequestId().equals(controlPlaneRequestId())
        || (tuple != null && !tuple.controlPlaneRequestId().equals(controlPlaneRequestId()))
        || !canonicalEquals(snapshot.get("authorityTuple"), value.get("authorityTuple"))
        || !canonicalEquals(snapshot.get("membershipVersion"), value.get("membershipVersion"))) {
      throw denied("Authority bundle does not bind the exact retained Account capture");
    }
    if (!Long.toString(positiveNumber(snapshot.get("issuanceFence"), "captured issuanceFence"))
        .equals(decoded.issuanceFence())) {
      throw denied("Account issuance fence differs from the retained source snapshot");
    }
  }

  public Map<String, Object> authorityTuple() {
    return decoded.authorityTuple();
  }

  public Map<String, Object> membershipVersion() {
    return decoded.membershipVersion();
  }

  public UUID issuanceOperationId() {
    return decoded.issuanceOperationId();
  }

  public String controlPlaneRequestId() {
    return decoded.controlPlaneRequestId();
  }

  public String authorizationExpiresAt() {
    return decoded.authorizationExpiresAt();
  }

  public String sourceEvidenceId() {
    return decoded.sourceEvidenceId();
  }

  public String sourceEvidenceVersion() {
    return decoded.sourceEvidenceVersion();
  }

  public long issuanceFence() {
    return positiveNumber(decoded.issuanceFence(), "Account issuance fence");
  }

  public UUID controlUiTokenJti() {
    return decoded.controlUiTokenJti();
  }

  public Map<String, Object> jsonValue() {
    return decoded.jsonValue();
  }

  private Instant originalReferenceExpiresAt() {
    return decoded.expiresAt();
  }

  static StartSessionAuthorityEvidenceBundle.BundleReference toSharedReference(
      BundleReference reference) {
    Objects.requireNonNull(reference, "original Account source reference is required");
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        reference.bundleVersion(),
        reference.sourceVersion(),
        reference.sourceFence(),
        reference.linearization());
  }

  static BundleReference fromSharedReference(
      StartSessionAuthorityEvidenceBundle.BundleReference reference) {
    Objects.requireNonNull(reference, "original Account source reference is required");
    return new BundleReference(
        reference.bundleVersion(),
        reference.sourceVersion(),
        reference.sourceFence(),
        reference.linearization());
  }

  private static BundleReference referenceForCaptureValue(
      AccountStartSessionAuthorityCapture capture) {
    return fromSharedReference(capture.bundleReference());
  }

  private static Map<String, Object> captureSnapshot(AccountStartSessionAuthorityCapture capture) {
    try {
      byte[] bytes = capture.snapshotBytes();
      Map<String, Object> snapshot =
          JSON.readValue(
              new String(bytes, java.nio.charset.StandardCharsets.UTF_8), new TypeReference<>() {});
      if (!CAPTURE_SNAPSHOT_FIELDS.equals(snapshot.keySet())
          || !"account-start-session-authority-snapshot/v1".equals(snapshot.get("schema"))
          || !MessageDigest.isEqual(bytes, AccountControlUiAuthority.canonical(snapshot))) {
        throw denied("Retained Account source snapshot is not exact canonical data");
      }
      return snapshot;
    } catch (RuntimeException malformed) {
      throw denied("Retained Account source snapshot is malformed");
    }
  }

  private static UUID canonicalUuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException malformed) {
      throw denied(field + " must be a canonical UUID");
    }
  }

  private static String positiveDecimalString(Object value, String field) {
    return Long.toString(positiveNumber(value, field));
  }

  private static long positive(long value, String field) {
    if (value <= 0L) throw denied(field + " must be positive");
    return value;
  }

  private static void requireCapturedTenantClaims(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID actor,
      UUID tenant,
      Map<String, Object> authorityTuple,
      Map<String, Object> membershipVersion,
      Stored stored,
      Map<String, Object> claims) {
    if (!tuple.actor().accountId().equals(actor)
        || !tuple.actor().accountId().equals(stored.accountId)
        || !tuple.action().scope().tenantId().equals(tenant)
        || !tuple.action().scope().tenantId().equals(stored.tenantId)
        || !"COMMITTED".equals(stored.status)
        || stored.jti == null
        || !stored.jti.toString().equals(claims.get("jti"))
        || !actor.toString().equals(claims.get("sub"))
        || !actor.toString().equals(claims.get("accountId"))
        || !"control-ui".equals(claims.get("aud"))
        || positiveNumber(claims.get("tokenGeneration"), "tokenGeneration") != 1L
        || claims.containsKey("assurance")
        || claims.containsKey("globalRoles")
        || !Map.of(tenant.toString(), List.of("tenantAdmin")).equals(claims.get("scopedRoles"))
        || !canonicalEquals(authorityTuple, claims.get("authorityTuple"))
        || !canonicalEquals(membershipVersion, claims.get("membershipVersion"))) {
      throw denied("Captured tenantAdmin control-ui evidence is required");
    }
  }

  private static Map<String, Object> humanTenantEvidence(
      UUID actor,
      UUID tenant,
      Map<String, Object> authorityTuple,
      UUID tokenJti,
      Map<String, Object> claims) {
    if (claims.containsKey("assurance") || claims.containsKey("globalRoles")) {
      throw denied("Tenant authority must omit assurance and global-role evidence");
    }
    long accountGeneration =
        positiveNumber(
            authorityTuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    Map<String, Object> tenantGenerations =
        object(authorityTuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    if (!tenantGenerations.keySet().equals(Set.of(tenant.toString()))) {
      throw denied("Exact selected tenant authority generation is required");
    }
    long tenantGeneration =
        positiveNumber(tenantGenerations.get(tenant.toString()), "tenantAuthorityGeneration");
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("evidenceType", HUMAN_EVIDENCE_TYPE);
    evidence.put("actorAccountId", actor.toString());
    evidence.put("controlUiTokenJti", tokenJti.toString());
    evidence.put("role", "tenantAdmin");
    evidence.put("accountGeneration", Long.toString(accountGeneration));
    evidence.put("tenantGeneration", Long.toString(tenantGeneration));
    return Map.copyOf(evidence);
  }

  private static void requireCurrentTenantClaims(
      StartSessionPreAuthorizationReservationTuple tuple,
      Snapshot source,
      Stored stored,
      Map<String, Object> claims) {
    if (!tuple.actor().accountId().equals(stored.accountId)
        || !tuple.actor().accountId().equals(source.actor())
        || !tuple.action().scope().tenantId().equals(stored.tenantId)
        || !tuple.action().scope().tenantId().equals(source.tenant())
        || !stored.jti.toString().equals(claims.get("jti"))
        || !stored.accountId.toString().equals(claims.get("sub"))
        || !stored.accountId.toString().equals(claims.get("accountId"))
        || !"control-ui".equals(claims.get("aud"))
        || positiveNumber(claims.get("tokenGeneration"), "tokenGeneration") != 1L
        || claims.containsKey("assurance")
        || claims.containsKey("globalRoles")
        || !Map.of(stored.tenantId.toString(), List.of("tenantAdmin"))
            .equals(claims.get("scopedRoles"))
        || !canonicalEquals(source.authorityTuple(), claims.get("authorityTuple"))
        || !canonicalEquals(source.membershipVersion(), claims.get("membershipVersion"))) {
      throw denied("Current tenantAdmin control-ui evidence is required");
    }
  }

  private static Map<String, Object> humanTenantEvidence(
      Snapshot source, UUID tokenJti, Map<String, Object> claims) {
    if (claims.containsKey("assurance") || claims.containsKey("globalRoles")) {
      throw denied("Tenant authority must omit assurance and global-role evidence");
    }
    Map<String, Object> tuple = source.authorityTuple();
    long accountGeneration =
        positiveNumber(tuple.get("accountAuthorityGeneration"), "accountAuthorityGeneration");
    Map<String, Object> tenantGenerations =
        object(tuple.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    if (!tenantGenerations.keySet().equals(Set.of(source.tenant().toString()))) {
      throw denied("Exact selected tenant authority generation is required");
    }
    long tenantGeneration =
        positiveNumber(
            tenantGenerations.get(source.tenant().toString()), "tenantAuthorityGeneration");
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("evidenceType", HUMAN_EVIDENCE_TYPE);
    evidence.put("actorAccountId", source.actor().toString());
    evidence.put("controlUiTokenJti", tokenJti.toString());
    evidence.put("role", "tenantAdmin");
    evidence.put("accountGeneration", Long.toString(accountGeneration));
    evidence.put("tenantGeneration", Long.toString(tenantGeneration));
    return Map.copyOf(evidence);
  }

  private static String sourceEvidenceId(
      byte[] sourceEvidence,
      BundleReference reference,
      long accountIssuanceFence,
      long accountIssuanceFenceSourceVersion) {
    Map<String, Object> value =
        Map.of(
            "schema",
            "account-start-session-source-capture-evidence/v1",
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceBytesBase64",
            Base64.getEncoder().encodeToString(sourceEvidence),
            "sourceVersion",
            reference.sourceVersion(),
            "sourceFence",
            reference.sourceFence(),
            "linearization",
            reference.linearization(),
            "accountIssuanceFence",
            Long.toString(positive(accountIssuanceFence, "Account issuance fence")),
            "accountIssuanceFenceSourceVersion",
            Long.toString(
                positive(
                    accountIssuanceFenceSourceVersion, "Account issuance fence source version")));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(AccountControlUiAuthority.canonical(value));
      return "sha256:" + java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private void requireCurrentSourceEvidence(Snapshot source, BundleReference reference) {
    if (!currentSourceMatchesEvidence(source, reference)) {
      throw denied(
          "Current Account source evidence or issuance fence differs from the original capture");
    }
  }

  private boolean currentSourceMatchesEvidence(Snapshot source, BundleReference reference) {
    Map<String, Object> projection =
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence");
    if (source.issuanceFence() <= 0L || source.issuanceFenceSourceVersion() <= 0L) return false;
    return Long.toString(source.issuanceFence()).equals(decoded.issuanceFence())
        && sourceEvidenceId(
                source.evidence(),
                reference,
                source.issuanceFence(),
                source.issuanceFenceSourceVersion())
            .equals(projection.get("sourceEvidenceId"));
  }

  private static void requireTimeWindow(Instant issuedAt, Instant expiresAt) {
    Objects.requireNonNull(issuedAt, "issue time is required");
    Objects.requireNonNull(expiresAt, "reference expiry is required");
    if (!issuedAt.isAfter(Instant.EPOCH)
        || !expiresAt.isAfter(issuedAt)
        || issuedAt.getNano() % 1_000_000 != 0
        || expiresAt.getNano() % 1_000_000 != 0) {
      throw denied("Operator evidence timestamps must be positive exact milliseconds");
    }
  }

  /**
   * V128 stores a fixed-width UTC millisecond timestamp, while the shared bundle contract uses the
   * canonical {@link Instant#toString()} representation. Preserve the captured instant and
   * precision while normalizing only that textual representation (notably, dropping .000).
   */
  private static String canonicalCaptureTimestamp(AccountStartSessionAuthorityCapture capture) {
    final Instant capturedAt;
    try {
      capturedAt = Instant.parse(capture.capturedAt());
    } catch (RuntimeException malformed) {
      throw denied("Capture timestamp is malformed");
    }
    if (capturedAt.getNano() % 1_000_000 != 0) {
      throw denied("Capture timestamp must use exact millisecond precision");
    }
    return capturedAt.toString();
  }

  private static Instant instant(String value) {
    try {
      Instant parsed = Instant.parse(value);
      if (!parsed.toString().equals(value)) {
        throw denied("Timestamp is not canonical UTC text");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw denied("Timestamp is malformed");
    }
  }

  private static boolean canonicalEquals(Object first, Object second) {
    if (first == null || second == null) return false;
    return MessageDigest.isEqual(
        AccountControlUiAuthority.canonical(first), AccountControlUiAuthority.canonical(second));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)
        || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
      throw denied(field + " must be an object");
    }
    return (Map<String, Object>) map;
  }

  private static void requireFields(Map<String, Object> value, Set<String> fields, String field) {
    if (value == null || !value.keySet().equals(fields)) {
      throw denied(field + " has missing or unsupported fields");
    }
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String text) || text.isEmpty()) {
      throw denied(field + " must be a nonempty string");
    }
    return text;
  }

  private static long positiveNumber(Object value, String field) {
    try {
      long parsed;
      if (value instanceof Number number) {
        parsed = Long.parseLong(number.toString());
      } else if (value instanceof String text && DECIMAL.matcher(text).matches()) {
        parsed = Long.parseLong(text);
      } else {
        throw new NumberFormatException();
      }
      if (parsed <= 0) throw new NumberFormatException();
      return parsed;
    } catch (RuntimeException malformed) {
      throw denied(field + " must be a positive canonical integer");
    }
  }

  private static IllegalArgumentException denied(String message) {
    return new IllegalArgumentException(message);
  }

  /** Original source reference is a separate typed object, not an extra bundle member. */
  public record BundleReference(
      String bundleVersion, String sourceVersion, String sourceFence, String linearization) {
    public BundleReference {
      new StartSessionAuthorityEvidenceBundle.BundleReference(
          bundleVersion, sourceVersion, sourceFence, linearization);
    }

    public Map<String, Object> asJsonValue() {
      return Map.of(
          "bundleVersion", bundleVersion,
          "sourceVersion", sourceVersion,
          "sourceFence", sourceFence,
          "linearization", linearization);
    }

    public static BundleReference fromJsonValue(Object raw) {
      Map<String, Object> value = object(raw, "bundleReference");
      requireFields(
          value,
          Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization"),
          "bundleReference");
      return new BundleReference(
          string(value.get("bundleVersion"), "bundleVersion"),
          string(value.get("sourceVersion"), "sourceVersion"),
          string(value.get("sourceFence"), "sourceFence"),
          string(value.get("linearization"), "linearization"));
    }
  }
}
