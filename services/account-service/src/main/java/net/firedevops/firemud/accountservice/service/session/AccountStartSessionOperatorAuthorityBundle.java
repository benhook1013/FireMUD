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
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository.OwnerLinearization;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository.Stored;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;

/** Exact ADR 0047 Account authority evidence for the tenantAdmin StartSession branch. */
public final class AccountStartSessionOperatorAuthorityBundle {
  public static final String BUNDLE_VERSION = StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION;
  public static final String HUMAN_EVIDENCE_TYPE =
      StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE;
  private static final Pattern DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");

  private final StartSessionAuthorityEvidenceBundle decoded;
  private final byte[] canonicalBytes;
  private final Map<String, Object> value;

  private AccountStartSessionOperatorAuthorityBundle(StartSessionAuthorityEvidenceBundle decoded) {
    this.decoded = Objects.requireNonNull(decoded, "shared authority bundle is required");
    this.canonicalBytes = decoded.canonicalBytes();
    this.value = decoded.jsonValue();
  }

  /**
   * Builds the one supported human branch from Account's already-current source snapshot and the
   * control-ui token row proven by {@link AccountControlUiActorService}.
   */
  public static AccountStartSessionOperatorAuthorityBundle create(
      StartSessionPreAuthorizationReservationTuple tuple,
      Snapshot source,
      Stored controlUiIssuance,
      UUID issuanceOperationId,
      OwnerLinearization linearization,
      Instant issuedAt,
      Instant referenceExpiresAt) {
    Objects.requireNonNull(tuple, "pre-authorization tuple is required");
    Objects.requireNonNull(source, "current Account source is required");
    Objects.requireNonNull(controlUiIssuance, "current control-ui issuance is required");
    Objects.requireNonNull(issuanceOperationId, "issuance operation identity is required");
    Objects.requireNonNull(linearization, "Account owner linearization is required");
    requireTimeWindow(issuedAt, referenceExpiresAt);

    if (!tuple.actor().accountId().equals(source.actor())
        || !tuple.action().scope().tenantId().equals(source.tenant())
        || !controlUiIssuance.accountId.equals(source.actor())
        || !controlUiIssuance.tenantId.equals(source.tenant())) {
      throw denied("Current Account and control-ui source must exactly match the typed actor");
    }

    Map<String, Object> claims =
        AccountControlUiIssuanceRepository.object(controlUiIssuance.claims);
    requireCurrentTenantClaims(tuple, source, controlUiIssuance, claims);
    SourceEvidence accountSource = uniqueAccountSource(source, source.actor());
    BundleReference reference =
        new BundleReference(
            BUNDLE_VERSION,
            accountSource.sourceVersion(),
            Long.toString(linearization.sourceFence()),
            linearization.transactionId());
    String sourceEvidenceId =
        sourceEvidenceId(source.evidence(), accountSource.sourceVersion(), reference);

    Map<String, Object> typedScope =
        Map.of(
            "tenantId", tuple.action().scope().tenantId().toString(),
            "targetNamespace", tuple.action().scope().targetNamespace());
    Map<String, Object> authorityScope =
        Map.of(
            "scope", typedScope,
            "actionFamily", tuple.actionFamily(),
            "applicableAccountId", source.actor().toString(),
            "applicableTenantId", source.tenant().toString());
    Map<String, Object> projection =
        Map.of(
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceId",
            sourceEvidenceId,
            "sourceEvidenceVersion",
            accountSource.sourceVersion(),
            "projectionStatus",
            "CURRENT",
            "evaluatedAt",
            issuedAt.toString(),
            "expiresAt",
            referenceExpiresAt.toString());
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
    Map<String, Object> humanEvidence = humanTenantEvidence(source, controlUiIssuance.jti, claims);

    Map<String, Object> bundle = new LinkedHashMap<>();
    bundle.put("bundleVersion", BUNDLE_VERSION);
    bundle.put("authorityScope", authorityScope);
    bundle.put("accountProjectionEvidence", projection);
    bundle.put("issuanceOperationIdentity", operationIdentity);
    bundle.put("issuanceKind", "human_operator");
    bundle.put("authorityTuple", source.authorityTuple());
    bundle.put("membershipVersion", source.membershipVersion());
    bundle.put("issuanceFence", reference.sourceFence());
    bundle.put("issuanceEvidence", humanEvidence);
    byte[] encoded = AccountControlUiAuthority.canonical(bundle);
    AccountStartSessionOperatorAuthorityBundle result = decode(encoded);
    result.requireTupleBinding(tuple);
    result.requireReferenceBinding(reference);
    result.requireCurrent(source, controlUiIssuance, tuple, reference, issuedAt);
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
    SourceEvidence source = uniqueAccountSource(currentSource, currentSource.actor());
    if (!source.sourceVersion().equals(projection.get("sourceEvidenceVersion"))
        || !source.sourceVersion().equals(originalReference.sourceVersion())
        || !sourceEvidenceId(currentSource.evidence(), source.sourceVersion(), originalReference)
            .equals(projection.get("sourceEvidenceId"))
        || !canonicalEquals(currentSource.authorityTuple(), value.get("authorityTuple"))
        || !canonicalEquals(currentSource.membershipVersion(), value.get("membershipVersion"))) {
      throw denied("Current Account source, generation, or membership changed");
    }
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
    SourceEvidence accountSource = uniqueAccountSource(currentSource, currentSource.actor());
    Map<String, Object> projection =
        object(value.get("accountProjectionEvidence"), "accountProjectionEvidence");
    if (!accountSource.sourceVersion().equals(originalReference.sourceVersion())
        || !accountSource.sourceVersion().equals(projection.get("sourceEvidenceVersion"))
        || !sourceEvidenceId(
                currentSource.evidence(), accountSource.sourceVersion(), originalReference)
            .equals(projection.get("sourceEvidenceId"))
        || !canonicalEquals(currentSource.authorityTuple(), value.get("authorityTuple"))
        || !canonicalEquals(currentSource.membershipVersion(), value.get("membershipVersion"))
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

  public BundleReference referenceForSource(Snapshot source, OwnerLinearization linearization) {
    SourceEvidence accountSource = uniqueAccountSource(source, source.actor());
    BundleReference reference =
        new BundleReference(
            BUNDLE_VERSION,
            accountSource.sourceVersion(),
            Long.toString(linearization.sourceFence()),
            linearization.transactionId());
    requireReferenceBinding(reference);
    if (!sourceEvidenceId(source.evidence(), accountSource.sourceVersion(), reference)
        .equals(sourceEvidenceId())) {
      throw denied("Account source reference does not identify this captured source");
    }
    return reference;
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

  public UUID controlUiTokenJti() {
    return decoded.controlUiTokenJti();
  }

  public Map<String, Object> jsonValue() {
    return decoded.jsonValue();
  }

  private Instant originalReferenceExpiresAt() {
    return decoded.expiresAt();
  }

  private void requireReferenceBinding(BundleReference reference) {
    decoded.requireReferenceBinding(toSharedReference(reference));
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
      byte[] sourceEvidence, String sourceVersion, BundleReference reference) {
    Map<String, Object> value =
        Map.of(
            "schema",
            "account-authority-source-linearization/v1",
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceBytesBase64",
            Base64.getEncoder().encodeToString(sourceEvidence),
            "sourceVersion",
            sourceVersion,
            "sourceFence",
            reference.sourceFence(),
            "transactionId",
            reference.linearization());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(AccountControlUiAuthority.canonical(value));
      return "sha256:" + java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static SourceEvidence uniqueAccountSource(Snapshot source, UUID actor) {
    List<SourceEvidence> matches =
        source.sources().stream()
            .filter(
                candidate ->
                    candidate.kind() == SourceKind.ACCOUNT
                        && actor.toString().equals(candidate.scopeId()))
            .toList();
    if (matches.size() != 1) {
      throw denied("One exact Account source version for the human actor is required");
    }
    return matches.getFirst();
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
