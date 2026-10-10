package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle.BundleReference;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Permanent Account-owned issuance and one-time redemption storage for StartSession operator
 * authorization.
 *
 * <p>Callers must authenticate the immediate workload peer and establish current Account authority
 * before invoking this repository. This storage is not authority evidence and a returned record is
 * not a source-snapshot guarantee. Callers enforce the immutable response-envelope expiry before
 * recovering or decrypting that envelope. All methods require the caller's ambient Account
 * transaction; this repository performs no network calls and creates no credentials, references, or
 * keys.
 */
@Repository
public class AccountStartSessionOperatorAuthorizationRepository {
  private static final String TABLE = "account_start_session_operator_authorizations";
  private static final String SELECT_COLUMNS =
      "control_plane_request_id, pre_authorization_tuple, mutation_digest, "
          + "issuance_workload_uri, reservation_owner_id, reservation_claim_fence, "
          + "issuance_operation_id, issuance_fence, bundle_version, bundle_source_version, "
          + "bundle_source_fence, bundle_linearization, authority_evidence_bundle, "
          + "authorization_reference_fingerprint, encrypted_response_envelope, issued_at, "
          + "reference_expires_at, response_envelope_expires_at, status, "
          + "redemption_redeemer_workload_uri, redemption_owner_attempt_id, "
          + "redemption_owner_fence, redeemed_at, redemption_reference_fingerprint, "
          + "redemption_authority_evidence_bundle";
  private static final int MAX_TUPLE_BYTES = 8 * 1024;
  private static final int MAX_AUTHORITY_BUNDLE_BYTES = 128 * 1024;
  private static final int MAX_ENCRYPTED_ENVELOPE_BYTES = 64 * 1024;
  private static final Duration MAX_REFERENCE_LIFETIME = Duration.ofMinutes(5);
  private static final Duration MAX_RESPONSE_RECOVERY_WINDOW = Duration.ofMinutes(1);
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern FINGERPRINT =
      Pattern.compile("arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}");
  private static final Pattern SPIFFE_WORKLOAD_URI =
      Pattern.compile(
          "spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/"
              + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Constructor validates the injected DSL collaborator without acquiring resources or exposing a partially initialized repository.")
  public AccountStartSessionOperatorAuthorizationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /**
   * Inserts the one permanent issuance row or returns its exact original output on duplicate.
   *
   * <p>The stable canonical control-plane request ID is the unique key. Duplicate comparison covers
   * every original pre-authorization binding; candidate output fields are deliberately not compared
   * or substituted when a row already exists.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CreateOrReadResult createOrReadExact(IssuanceCandidate candidate) {
    requireOwnerTransaction();
    validateCandidate(candidate);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (control_plane_request_id, pre_authorization_tuple, mutation_digest, "
                + "issuance_workload_uri, reservation_owner_id, reservation_claim_fence, "
                + "issuance_operation_id, issuance_fence, bundle_version, bundle_source_version, "
                + "bundle_source_fence, bundle_linearization, authority_evidence_bundle, "
                + "authorization_reference_fingerprint, encrypted_response_envelope, issued_at, "
                + "reference_expires_at, response_envelope_expires_at, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED') "
                + "ON CONFLICT (control_plane_request_id) DO NOTHING",
            candidate.controlPlaneRequestId(),
            candidate.preAuthorizationTuple(),
            candidate.mutationDigest(),
            candidate.issuanceWorkloadUri(),
            candidate.reservationOwnerId(),
            candidate.reservationClaimFence(),
            candidate.issuanceOperationId(),
            candidate.issuanceFence(),
            candidate.bundleReference().bundleVersion(),
            candidate.bundleReference().sourceVersion(),
            candidate.bundleReference().sourceFence(),
            candidate.bundleReference().linearization(),
            candidate.authorityEvidenceBundle(),
            candidate.authorizationReferenceFingerprint(),
            candidate.encryptedResponseEnvelope(),
            utc(candidate.issuedAt()),
            utc(candidate.referenceExpiresAt()),
            utc(candidate.responseEnvelopeExpiresAt()));

    Record row = lockByRequestId(candidate.controlPlaneRequestId());
    IssuanceRecord original = toIssuanceRecord(row);
    requireSameOriginalBinding(candidate, original);
    return new CreateOrReadResult(inserted == 1, original);
  }

  /** Reads the permanent issuance record by its stable canonical request ID. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<IssuanceRecord> findByControlPlaneRequestId(String controlPlaneRequestId) {
    requireOwnerTransaction();
    StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
        controlPlaneRequestId);
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE + " WHERE control_plane_request_id = ?",
            controlPlaneRequestId);
    return Optional.ofNullable(row == null ? null : toIssuanceRecord(row));
  }

  /**
   * Atomically consumes an unexpired exact reference binding for one owner attempt.
   *
   * <p>The stored redemption projection contains only the immutable fingerprint and the exact
   * source-bound authority bundle. An exact retry returns that same projection with {@code
   * replay=true}; it does not authorize a different owner attempt.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RedemptionResult redeemExact(RedemptionRequest request) {
    requireOwnerTransaction();
    validateRedemptionRequest(request);
    Record row = lockByRequestId(request.controlPlaneRequestId());
    if (row == null) {
      throw unavailable();
    }
    IssuanceRecord issuance = toIssuanceRecord(row);
    requireSameRedemptionBinding(request, issuance);

    if (issuance.status() == Status.REDEEMED) {
      if (!request.redeemerWorkloadUri().equals(issuance.redemptionRedeemerWorkloadUri())
          || !request.ownerAttemptId().equals(issuance.redemptionOwnerAttemptId())
          || request.ownerFence() != issuance.redemptionOwnerFence()) {
        throw conflict();
      }
      return redemptionResult(issuance, true);
    }
    if (issuance.referenceExpiresAt().compareTo(request.now()) <= 0) {
      throw new ReferenceExpiredException();
    }

    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'REDEEMED', redemption_redeemer_workload_uri = ?, "
                + "redemption_owner_attempt_id = ?, redemption_owner_fence = ?, redeemed_at = ?, "
                + "redemption_reference_fingerprint = ?, "
                + "redemption_authority_evidence_bundle = ? "
                + "WHERE control_plane_request_id = ? AND status = 'ISSUED' "
                + "AND reference_expires_at > ?",
            request.redeemerWorkloadUri(),
            request.ownerAttemptId(),
            request.ownerFence(),
            utc(request.now()),
            request.authorizationReferenceFingerprint(),
            request.authorityEvidenceBundle(),
            request.controlPlaneRequestId(),
            utc(request.now()));
    if (changed != 1) {
      throw unavailable();
    }

    Record redeemedRow = lockByRequestId(request.controlPlaneRequestId());
    IssuanceRecord redeemed = toIssuanceRecord(redeemedRow);
    return redemptionResult(redeemed, false);
  }

  private Record lockByRequestId(String requestId) {
    return dsl.fetchOne(
        "SELECT "
            + SELECT_COLUMNS
            + " FROM "
            + TABLE
            + " WHERE control_plane_request_id = ? FOR UPDATE",
        requestId);
  }

  private IssuanceRecord toIssuanceRecord(Record row) {
    if (row == null) {
      throw unavailable();
    }
    IssuanceRecord issuance =
        new IssuanceRecord(
            requiredControlPlaneRequestId(row.get("control_plane_request_id", String.class)),
            requiredBytes(row, "pre_authorization_tuple", MAX_TUPLE_BYTES),
            requiredDigest(row.get("mutation_digest", String.class)),
            requiredWorkloadUri(row.get("issuance_workload_uri", String.class)),
            requiredUuid(row.get("reservation_owner_id", UUID.class), "reservation owner ID"),
            requiredPositive(
                row.get("reservation_claim_fence", Long.class), "reservation claim fence"),
            requiredUuid(row.get("issuance_operation_id", UUID.class), "issuance operation ID"),
            requiredPositive(row.get("issuance_fence", Long.class), "issuance fence"),
            bundleReference(row),
            requiredBytes(row, "authority_evidence_bundle", MAX_AUTHORITY_BUNDLE_BYTES),
            requiredFingerprint(row.get("authorization_reference_fingerprint", String.class)),
            requiredBytes(row, "encrypted_response_envelope", MAX_ENCRYPTED_ENVELOPE_BYTES),
            requiredInstant(row.get("issued_at", OffsetDateTime.class), "issue time"),
            requiredInstant(
                row.get("reference_expires_at", OffsetDateTime.class), "reference expiry"),
            requiredInstant(
                row.get("response_envelope_expires_at", OffsetDateTime.class),
                "response-envelope expiry"),
            parseStatus(row.get("status", String.class)),
            optionalWorkloadUri(row.get("redemption_redeemer_workload_uri", String.class)),
            optionalUuid(row.get("redemption_owner_attempt_id", UUID.class), "owner attempt ID"),
            optionalPositive(row.get("redemption_owner_fence", Long.class), "owner fence"),
            optionalInstant(row.get("redeemed_at", OffsetDateTime.class), "redemption time"),
            optionalFingerprint(row.get("redemption_reference_fingerprint", String.class)),
            optionalBytes(row, "redemption_authority_evidence_bundle", MAX_AUTHORITY_BUNDLE_BYTES));
    validateDurableRecord(issuance);
    return issuance;
  }

  private static void validateDurableRecord(IssuanceRecord issuance) {
    try {
      StartSessionPreAuthorizationReservationTuple tuple =
          decodeTuple(issuance.preAuthorizationTuple());
      if (!issuance.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
          || !tuple.mutationDigest().equals(issuance.mutationDigest())
          || !issuance.issuanceWorkloadUri().endsWith("/sa/logging-admin-service")
          || issuance.reservationClaimFence() <= 0L
          || issuance.issuanceFence() <= 0L
          || !Long.toString(issuance.issuanceFence())
              .equals(issuance.bundleReference().sourceFence())
          || !issuance.referenceExpiresAt().isAfter(issuance.issuedAt())
          || issuance.referenceExpiresAt().isAfter(issuance.issuedAt().plus(MAX_REFERENCE_LIFETIME))
          || !issuance.responseEnvelopeExpiresAt().isAfter(issuance.referenceExpiresAt())
          || issuance
              .responseEnvelopeExpiresAt()
              .isAfter(issuance.referenceExpiresAt().plus(MAX_RESPONSE_RECOVERY_WINDOW))) {
        throw unavailable();
      }
      if (issuance.status() == Status.REDEEMED
          && (!ownerWorkloadUri(issuance.issuanceWorkloadUri(), issuance.preAuthorizationTuple())
                  .equals(issuance.redemptionRedeemerWorkloadUri())
              || !issuance
                  .authorizationReferenceFingerprint()
                  .equals(issuance.redemptionReferenceFingerprint())
              || !Arrays.equals(
                  issuance.authorityEvidenceBundle(),
                  issuance.redemptionAuthorityEvidenceBundle()))) {
        throw unavailable();
      }
    } catch (IllegalStateException stateFailure) {
      throw stateFailure;
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static void validateCandidate(IssuanceCandidate candidate) {
    Objects.requireNonNull(candidate, "issuance candidate is required");
    StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
        candidate.controlPlaneRequestId());
    byte[] tupleBytes = requireBytes(candidate.preAuthorizationTuple(), MAX_TUPLE_BYTES, "tuple");
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(tupleBytes);
    if (!candidate.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())
        || !tuple.mutationDigest().equals(candidate.mutationDigest())) {
      throw new IllegalArgumentException("pre-authorization tuple identity or digest mismatches");
    }
    requireDigest(candidate.mutationDigest());
    String issuer = requireWorkloadUri(candidate.issuanceWorkloadUri());
    if (!issuer.endsWith("/sa/logging-admin-service")) {
      throw new IllegalArgumentException("StartSession issuer must be Logging/Admin workload");
    }
    requireNonNilUuid(candidate.reservationOwnerId(), "reservationOwnerId");
    requirePositive(candidate.reservationClaimFence(), "reservationClaimFence");
    requireNonNilUuid(candidate.issuanceOperationId(), "issuanceOperationId");
    requirePositive(candidate.issuanceFence(), "issuanceFence");
    requireBundleReference(candidate.bundleReference(), candidate.issuanceFence());
    requireBytes(
        candidate.authorityEvidenceBundle(), MAX_AUTHORITY_BUNDLE_BYTES, "authority bundle");
    requireFingerprint(candidate.authorizationReferenceFingerprint());
    requireBytes(
        candidate.encryptedResponseEnvelope(),
        MAX_ENCRYPTED_ENVELOPE_BYTES,
        "encrypted response envelope");
    Objects.requireNonNull(candidate.issuedAt(), "issue time is required");
    Objects.requireNonNull(candidate.referenceExpiresAt(), "reference expiry is required");
    Objects.requireNonNull(
        candidate.responseEnvelopeExpiresAt(), "response-envelope expiry is required");
    if (!candidate.issuedAt().isAfter(Instant.EPOCH)
        || !candidate.referenceExpiresAt().isAfter(candidate.issuedAt())
        || candidate.referenceExpiresAt().isAfter(candidate.issuedAt().plus(MAX_REFERENCE_LIFETIME))
        || !candidate.responseEnvelopeExpiresAt().isAfter(candidate.referenceExpiresAt())
        || candidate
            .responseEnvelopeExpiresAt()
            .isAfter(candidate.referenceExpiresAt().plus(MAX_RESPONSE_RECOVERY_WINDOW))) {
      throw new IllegalArgumentException("authorization reference lifetime is outside its bound");
    }
  }

  private static void validateRedemptionRequest(RedemptionRequest request) {
    Objects.requireNonNull(request, "redemption request is required");
    StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
        request.controlPlaneRequestId());
    byte[] tupleBytes = requireBytes(request.preAuthorizationTuple(), MAX_TUPLE_BYTES, "tuple");
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(tupleBytes);
    if (!request.controlPlaneRequestId().equals(tuple.controlPlaneRequestId())) {
      throw new IllegalArgumentException("pre-authorization tuple request ID mismatches");
    }
    requireDigest(tuple.mutationDigest());
    requireFingerprint(request.authorizationReferenceFingerprint());
    requireNonNilUuid(request.reservationOwnerId(), "reservationOwnerId");
    requirePositive(request.reservationClaimFence(), "reservationClaimFence");
    String redeemer = requireWorkloadUri(request.redeemerWorkloadUri());
    if (!redeemer.endsWith("/sa/" + tuple.targetOwner())) {
      throw new IllegalArgumentException("redeemer workload does not match the typed target owner");
    }
    requireNonNilUuid(request.ownerAttemptId(), "ownerAttemptId");
    requirePositive(request.ownerFence(), "ownerFence");
    requireBytes(request.authorityEvidenceBundle(), MAX_AUTHORITY_BUNDLE_BYTES, "authority bundle");
    Objects.requireNonNull(request.now(), "redemption time is required");
    if (!request.now().isAfter(Instant.EPOCH)) {
      throw new IllegalArgumentException("redemption time must be after the Unix epoch");
    }
  }

  private static void requireSameOriginalBinding(
      IssuanceCandidate candidate, IssuanceRecord original) {
    if (!Arrays.equals(candidate.preAuthorizationTuple(), original.preAuthorizationTuple())
        || !candidate.mutationDigest().equals(original.mutationDigest())
        || !candidate.issuanceWorkloadUri().equals(original.issuanceWorkloadUri())
        || !candidate.reservationOwnerId().equals(original.reservationOwnerId())
        || candidate.reservationClaimFence() != original.reservationClaimFence()) {
      throw conflict();
    }
  }

  private static BundleReference bundleReference(Record row) {
    return new BundleReference(
        row.get("bundle_version", String.class),
        row.get("bundle_source_version", String.class),
        row.get("bundle_source_fence", String.class),
        row.get("bundle_linearization", String.class));
  }

  private static void requireBundleReference(BundleReference reference, long issuanceFence) {
    Objects.requireNonNull(reference, "original authority bundle reference is required");
    if (!Long.toString(issuanceFence).equals(reference.sourceFence())) {
      throw new IllegalArgumentException(
          "original bundle source fence must equal the Account issuance fence");
    }
  }

  private static void requireSameRedemptionBinding(
      RedemptionRequest request, IssuanceRecord issuance) {
    if (!Arrays.equals(request.preAuthorizationTuple(), issuance.preAuthorizationTuple())
        || !request
            .authorizationReferenceFingerprint()
            .equals(issuance.authorizationReferenceFingerprint())
        || !request.reservationOwnerId().equals(issuance.reservationOwnerId())
        || request.reservationClaimFence() != issuance.reservationClaimFence()
        || !Arrays.equals(request.authorityEvidenceBundle(), issuance.authorityEvidenceBundle())
        || !request
            .redeemerWorkloadUri()
            .equals(
                ownerWorkloadUri(
                    issuance.issuanceWorkloadUri(), request.preAuthorizationTuple()))) {
      throw conflict();
    }
  }

  private static String ownerWorkloadUri(String issuerWorkloadUri, byte[] tupleBytes) {
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(tupleBytes);
    int serviceSeparator = issuerWorkloadUri.lastIndexOf("/sa/");
    if (serviceSeparator < 0) {
      throw unavailable();
    }
    return issuerWorkloadUri.substring(0, serviceSeparator) + "/sa/" + tuple.targetOwner();
  }

  private static RedemptionResult redemptionResult(IssuanceRecord issuance, boolean replay) {
    if (issuance.status() != Status.REDEEMED
        || !issuance
            .authorizationReferenceFingerprint()
            .equals(issuance.redemptionReferenceFingerprint())
        || !Arrays.equals(
            issuance.authorityEvidenceBundle(), issuance.redemptionAuthorityEvidenceBundle())) {
      throw unavailable();
    }
    return new RedemptionResult(
        issuance.authorizationReferenceFingerprint(),
        issuance.redemptionAuthorityEvidenceBundle(),
        issuance.issuanceOperationId(),
        issuance.issuanceFence(),
        replay);
  }

  private static StartSessionPreAuthorizationReservationTuple decodeTuple(byte[] bytes) {
    String json = new String(bytes, StandardCharsets.UTF_8);
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(json);
    if (!Arrays.equals(bytes, tuple.canonicalJson().getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("pre-authorization tuple bytes are not canonical UTF-8");
    }
    return tuple;
  }

  private static String requiredControlPlaneRequestId(String value) {
    try {
      return StartSessionPreAuthorizationReservationTuple.requireCanonicalControlPlaneRequestId(
          value);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static String requireWorkloadUri(String value) {
    if (value == null
        || value.length() > 256
        || !SPIFFE_WORKLOAD_URI.matcher(value).matches()
        || !URI.create(value).toASCIIString().equals(value)) {
      throw new IllegalArgumentException("authenticated SPIFFE workload URI is malformed");
    }
    return value;
  }

  private static String requiredWorkloadUri(String value) {
    try {
      return requireWorkloadUri(value);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static String optionalWorkloadUri(String value) {
    return value == null ? null : requiredWorkloadUri(value);
  }

  private static String requireDigest(String value) {
    if (value == null || !DIGEST.matcher(value).matches()) {
      throw new IllegalArgumentException("mutation digest is malformed");
    }
    return value;
  }

  private static String requiredDigest(String value) {
    try {
      return requireDigest(value);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static String requireFingerprint(String value) {
    if (value == null || !FINGERPRINT.matcher(value).matches()) {
      throw new IllegalArgumentException("authorization-reference fingerprint is malformed");
    }
    return value;
  }

  private static String requiredFingerprint(String value) {
    try {
      return requireFingerprint(value);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static String optionalFingerprint(String value) {
    return value == null ? null : requiredFingerprint(value);
  }

  private static byte[] requireBytes(byte[] value, int maximum, String field) {
    if (value == null || value.length == 0 || value.length > maximum) {
      throw new IllegalArgumentException(field + " bytes are missing or exceed their bound");
    }
    return value;
  }

  private static byte[] requiredBytes(Record row, String field, int maximum) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0 || value.length > maximum) {
      throw unavailable();
    }
    return value;
  }

  private static byte[] optionalBytes(Record row, String field, int maximum) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) {
      return null;
    }
    if (value.length == 0 || value.length > maximum) {
      throw unavailable();
    }
    return value;
  }

  private static UUID requiredUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw unavailable();
    }
    return value;
  }

  private static UUID optionalUuid(UUID value, String field) {
    return value == null ? null : requiredUuid(value, field);
  }

  private static long requiredPositive(Long value, String field) {
    if (value == null || value <= 0L) {
      throw unavailable();
    }
    return value;
  }

  private static Long optionalPositive(Long value, String field) {
    if (value == null) {
      return null;
    }
    if (value <= 0L) {
      throw unavailable();
    }
    return value;
  }

  private static Instant requiredInstant(OffsetDateTime value, String field) {
    if (value == null || !value.toInstant().isAfter(Instant.EPOCH)) {
      throw unavailable();
    }
    return value.toInstant();
  }

  private static Instant optionalInstant(OffsetDateTime value, String field) {
    return value == null ? null : requiredInstant(value, field);
  }

  private static Status parseStatus(String value) {
    try {
      return Status.valueOf(value);
    } catch (RuntimeException malformed) {
      throw unavailable();
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "StartSession operator authorization storage requires an active Account transaction");
    }
  }

  private static UUID requireNonNilUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical nonnil UUID");
    }
    return value;
  }

  private static long requirePositive(long value, String field) {
    if (value <= 0L) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static IllegalStateException conflict() {
    return new IllegalStateException(
        "StartSession operator authorization request conflicts with its immutable binding");
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Exact StartSession operator authorization evidence unavailable");
  }

  public enum Status {
    ISSUED,
    REDEEMED
  }

  public record CreateOrReadResult(boolean created, IssuanceRecord issuance) {
    public CreateOrReadResult {
      Objects.requireNonNull(issuance, "issuance record is required");
    }
  }

  /** Candidate output fields must be created by the Account authority service, never this store. */
  public record IssuanceCandidate(
      String controlPlaneRequestId,
      byte[] preAuthorizationTuple,
      String mutationDigest,
      String issuanceWorkloadUri,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID issuanceOperationId,
      long issuanceFence,
      BundleReference bundleReference,
      byte[] authorityEvidenceBundle,
      String authorizationReferenceFingerprint,
      byte[] encryptedResponseEnvelope,
      Instant issuedAt,
      Instant referenceExpiresAt,
      Instant responseEnvelopeExpiresAt) {
    public IssuanceCandidate {
      Objects.requireNonNull(bundleReference, "original authority bundle reference is required");
      preAuthorizationTuple = copy(preAuthorizationTuple);
      authorityEvidenceBundle = copy(authorityEvidenceBundle);
      encryptedResponseEnvelope = copy(encryptedResponseEnvelope);
    }

    @Override
    public byte[] preAuthorizationTuple() {
      return copy(preAuthorizationTuple);
    }

    @Override
    public byte[] authorityEvidenceBundle() {
      return copy(authorityEvidenceBundle);
    }

    @Override
    public byte[] encryptedResponseEnvelope() {
      return copy(encryptedResponseEnvelope);
    }

    @Override
    public String toString() {
      return "IssuanceCandidate[controlPlaneRequestId="
          + controlPlaneRequestId
          + ", tuple/evidence/envelope redacted]";
    }
  }

  /** Immutable durable row view; raw references appear only inside its encrypted envelope. */
  public record IssuanceRecord(
      String controlPlaneRequestId,
      byte[] preAuthorizationTuple,
      String mutationDigest,
      String issuanceWorkloadUri,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID issuanceOperationId,
      long issuanceFence,
      BundleReference bundleReference,
      byte[] authorityEvidenceBundle,
      String authorizationReferenceFingerprint,
      byte[] encryptedResponseEnvelope,
      Instant issuedAt,
      Instant referenceExpiresAt,
      Instant responseEnvelopeExpiresAt,
      Status status,
      String redemptionRedeemerWorkloadUri,
      UUID redemptionOwnerAttemptId,
      Long redemptionOwnerFence,
      Instant redeemedAt,
      String redemptionReferenceFingerprint,
      byte[] redemptionAuthorityEvidenceBundle) {
    public IssuanceRecord {
      preAuthorizationTuple = copy(preAuthorizationTuple);
      authorityEvidenceBundle = copy(authorityEvidenceBundle);
      encryptedResponseEnvelope = copy(encryptedResponseEnvelope);
      redemptionAuthorityEvidenceBundle = copy(redemptionAuthorityEvidenceBundle);
      Objects.requireNonNull(status, "status is required");
      requireBundleReference(bundleReference, issuanceFence);
      if (status == Status.ISSUED
          && (redemptionRedeemerWorkloadUri != null
              || redemptionOwnerAttemptId != null
              || redemptionOwnerFence != null
              || redeemedAt != null
              || redemptionReferenceFingerprint != null
              || redemptionAuthorityEvidenceBundle != null)) {
        throw unavailable();
      }
      if (status == Status.REDEEMED
          && (redemptionRedeemerWorkloadUri == null
              || redemptionOwnerAttemptId == null
              || redemptionOwnerFence == null
              || redeemedAt == null
              || redemptionReferenceFingerprint == null
              || redemptionAuthorityEvidenceBundle == null)) {
        throw unavailable();
      }
    }

    @Override
    public byte[] preAuthorizationTuple() {
      return copy(preAuthorizationTuple);
    }

    @Override
    public byte[] authorityEvidenceBundle() {
      return copy(authorityEvidenceBundle);
    }

    @Override
    public byte[] encryptedResponseEnvelope() {
      return copy(encryptedResponseEnvelope);
    }

    @Override
    public byte[] redemptionAuthorityEvidenceBundle() {
      return copy(redemptionAuthorityEvidenceBundle);
    }

    @Override
    public String toString() {
      return "IssuanceRecord[controlPlaneRequestId="
          + controlPlaneRequestId
          + ", status="
          + status
          + ", tuple/evidence/envelope redacted]";
    }
  }

  public record RedemptionRequest(
      String controlPlaneRequestId,
      byte[] preAuthorizationTuple,
      String authorizationReferenceFingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      String redeemerWorkloadUri,
      UUID ownerAttemptId,
      long ownerFence,
      byte[] authorityEvidenceBundle,
      Instant now) {
    public RedemptionRequest {
      preAuthorizationTuple = copy(preAuthorizationTuple);
      authorityEvidenceBundle = copy(authorityEvidenceBundle);
    }

    @Override
    public byte[] preAuthorizationTuple() {
      return copy(preAuthorizationTuple);
    }

    @Override
    public byte[] authorityEvidenceBundle() {
      return copy(authorityEvidenceBundle);
    }

    @Override
    public String toString() {
      return "RedemptionRequest[controlPlaneRequestId="
          + controlPlaneRequestId
          + ", tuple/evidence redacted]";
    }
  }

  /** The one-time result contains authority projection only; it never contains the opaque ref. */
  public record RedemptionResult(
      String authorizationReferenceFingerprint,
      byte[] authorityEvidenceBundle,
      UUID issuanceOperationId,
      long issuanceFence,
      boolean replay) {
    public RedemptionResult {
      authorityEvidenceBundle = copy(authorityEvidenceBundle);
    }

    @Override
    public byte[] authorityEvidenceBundle() {
      return copy(authorityEvidenceBundle);
    }

    @Override
    public String toString() {
      return "RedemptionResult[issuanceOperationId="
          + issuanceOperationId
          + ", replay="
          + replay
          + ", authority bundle redacted]";
    }
  }

  public static final class ReferenceExpiredException extends IllegalStateException {
    private ReferenceExpiredException() {
      super("StartSession operator authorization reference is expired");
    }
  }

  private static byte[] copy(byte[] value) {
    return value == null ? null : value.clone();
  }
}
