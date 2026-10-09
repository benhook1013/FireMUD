package net.firedevops.firemud.gamesession.repository;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered, non-activating durable reservation for one canonical StartSession owner attempt.
 *
 * <p>This repository records immutable intent before the Account redemption call. It does not
 * perform redemption, infer authority, dispatch a Game Session mutation, or write a business
 * result. An expired claim remains pending and cannot be transferred or re-armed here.
 */
public final class GameSessionStartSessionOperatorAttemptRepository {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String TABLE = "game_session_start_session_operator_attempt";
  private static final String OWNER_FENCE_SEQUENCE =
      "game_session_start_session_operator_owner_fence_seq";
  private static final Pattern AUTHORIZATION_REFERENCE_FINGERPRINT =
      Pattern.compile("arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Duration MAX_OWNER_CLAIM_LEASE = Duration.ofMinutes(5);

  private final DSLContext dsl;
  private final long ownerClaimLeaseMillis;

  public GameSessionStartSessionOperatorAttemptRepository(
      DSLContext dsl, Duration ownerClaimLease) {
    this.dsl = Objects.requireNonNull(dsl, "dsl is required");
    Objects.requireNonNull(ownerClaimLease, "owner claim lease is required");
    long millis = ownerClaimLease.toMillis();
    if (millis <= 0L || ownerClaimLease.compareTo(MAX_OWNER_CLAIM_LEASE) > 0) {
      throw new IllegalArgumentException(
          "owner claim lease must be positive and at most five minutes");
    }
    this.ownerClaimLeaseMillis = millis;
  }

  /**
   * Creates one claim or returns the exact existing attempt without granting a second claim.
   * Requires a caller-owned, writable READ COMMITTED transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public ReservationResult reserve(StartSessionPostAuthorizationExecutionTuple tuple) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(tuple, "post-authorization tuple is required");
    String namespace = tuple.preAuthorizationTuple().action().scope().targetNamespace();
    String requestId = tuple.controlPlaneRequestId();
    lockRequestKey(namespace, requestId);

    Record existing = selectAttempt(namespace, requestId, true);
    if (existing != null) {
      byte[] storedTuple = requiredBytes(existing, "post_authorization_execution_tuple");
      if (!Arrays.equals(storedTuple, tuple.canonicalBytes())) {
        throw new StartSessionOperatorAttemptConflictException(
            "controlPlaneRequestId is already bound to a different complete post-authorization tuple");
      }
      requireStoredProjectionColumnsMatch(existing, tuple);
      return new ReservationResult(
          ReservationDisposition.EXACT_REPLAY, snapshot(existing), Optional.empty());
    }

    UUID attemptId = newNonNilUuid("ownerAttemptId");
    UUID ownerMutationId = newNonNilUuid("ownerMutationId");
    UUID claimOwnerId = newNonNilUuid("claimOwnerId");
    Record fenceRecord = dsl.fetchOne("SELECT nextval('" + OWNER_FENCE_SEQUENCE + "') AS fence");
    long ownerFence = requiredLong(fenceRecord, "fence");
    if (ownerFence <= 0L) {
      throw new IllegalStateException(
          "Game Session owner fence sequence did not produce a positive value");
    }

    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + TABLE
                + " (target_namespace, control_plane_request_id, canonical_tenant_id, "
                + "owner_attempt_id, owner_mutation_id, claim_owner_id, owner_fence, "
                + "post_authorization_execution_tuple, mutation_digest, "
                + "authorization_reference_fingerprint, lease_expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "clock_timestamp() + (?::double precision / 1000.0) * interval '1 second') "
                + "RETURNING *",
            namespace,
            requestId,
            tuple.preAuthorizationTuple().action().scope().tenantId(),
            attemptId,
            ownerMutationId,
            claimOwnerId,
            ownerFence,
            tuple.canonicalBytes(),
            tuple.mutationDigest(),
            tuple.authorizationReferenceFingerprint(),
            ownerClaimLeaseMillis);
    if (inserted == null) {
      throw new IllegalStateException("Game Session owner attempt insert returned no row");
    }
    AttemptClaim claim =
        new AttemptClaim(
            namespace, requestId, attemptId, ownerMutationId, claimOwnerId, ownerFence);
    return new ReservationResult(
        ReservationDisposition.CLAIM_CREATED, snapshot(inserted), Optional.of(claim));
  }

  /**
   * Validates the exact original claim and returns its durable snapshot. Expired claims are stale;
   * they are deliberately not advanced or reallocated.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public AttemptSnapshot validateCurrentClaim(AttemptClaim claim) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(claim, "owner attempt claim is required");
    Record row = selectAttempt(claim.targetNamespace(), claim.controlPlaneRequestId(), true);
    requireClaimMatch(row, claim);
    requireUnexpired(row);
    return snapshot(row);
  }

  /**
   * Atomically attaches the exact Account redemption projection to its still-current claim. Replays
   * of the same projection return the original snapshot; any substitution conflicts.
   */
  @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
  public AttemptSnapshot attachAccountRedemptionProjection(
      AttemptClaim claim, AccountRedemptionProjection projection) {
    requireWritableReadCommittedOwnerTransaction();
    Objects.requireNonNull(claim, "owner attempt claim is required");
    Objects.requireNonNull(projection, "Account redemption projection is required");
    Record row = selectAttempt(claim.targetNamespace(), claim.controlPlaneRequestId(), true);
    requireClaimMatch(row, claim);
    requireUnexpired(row);
    StartSessionPostAuthorizationExecutionTuple tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(
            requiredBytes(row, "post_authorization_execution_tuple"));
    requireProjectionMatches(tuple, projection);

    byte[] existingProjection = optionalBytes(row, "account_redemption_projection");
    byte[] exactProjection = projection.canonicalBytes();
    if (existingProjection != null) {
      if (!Arrays.equals(existingProjection, exactProjection)) {
        throw new StartSessionOperatorAttemptConflictException(
            "Account redemption projection differs from the original exact projection");
      }
      return snapshot(row);
    }

    Record updated =
        dsl.fetchOne(
            "UPDATE "
                + TABLE
                + " SET account_redemption_projection = ? "
                + "WHERE target_namespace = ? AND control_plane_request_id = ? "
                + "AND owner_attempt_id = ? AND owner_mutation_id = ? "
                + "AND claim_owner_id = ? AND owner_fence = ? "
                + "AND lease_expires_at > clock_timestamp() "
                + "AND account_redemption_projection IS NULL RETURNING *",
            exactProjection,
            claim.targetNamespace(),
            claim.controlPlaneRequestId(),
            claim.ownerAttemptId(),
            claim.ownerMutationId(),
            claim.claimOwnerId(),
            claim.ownerFence());
    if (updated == null) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Game Session owner attempt claim changed before Account projection attachment");
    }
    return snapshot(updated);
  }

  private void lockRequestKey(String namespace, String requestId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || ?, 0))", namespace, requestId);
  }

  private Record selectAttempt(String namespace, String requestId, boolean forUpdate) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + TABLE
            + " WHERE target_namespace = ? AND control_plane_request_id = ?"
            + (forUpdate ? " FOR UPDATE" : ""),
        namespace,
        requestId);
  }

  private static void requireStoredProjectionColumnsMatch(
      Record row, StartSessionPostAuthorizationExecutionTuple tuple) {
    if (!tuple
            .preAuthorizationTuple()
            .action()
            .scope()
            .tenantId()
            .equals(requiredUuid(row, "canonical_tenant_id"))
        || !tuple.mutationDigest().equals(requiredText(row, "mutation_digest"))
        || !tuple
            .authorizationReferenceFingerprint()
            .equals(requiredText(row, "authorization_reference_fingerprint"))) {
      throw new IllegalStateException(
          "Persisted Game Session owner attempt columns differ from its exact canonical tuple");
    }
  }

  private static void requireProjectionMatches(
      StartSessionPostAuthorizationExecutionTuple tuple, AccountRedemptionProjection projection) {
    if (!tuple
            .authorizationReferenceFingerprint()
            .equals(projection.authorizationReferenceFingerprint())
        || !Arrays.equals(
            tuple.authorityEvidenceBundleBytes(), projection.authorityEvidenceBundleBytes())
        || !StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes())
            .issuanceOperationId()
            .equals(projection.issuanceOperationId())
        || parsePositiveFence(tuple.issuanceFence()) != projection.issuanceFence()) {
      throw new StartSessionOperatorAttemptConflictException(
          "Account redemption projection is not bound to the exact post-authorization tuple");
    }
  }

  private static void requireClaimMatch(Record row, AttemptClaim claim) {
    if (row == null
        || !claim.ownerAttemptId().equals(optionalUuid(row, "owner_attempt_id"))
        || !claim.ownerMutationId().equals(optionalUuid(row, "owner_mutation_id"))
        || !claim.claimOwnerId().equals(optionalUuid(row, "claim_owner_id"))
        || claim.ownerFence() != optionalLong(row, "owner_fence")) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Game Session owner attempt claim is missing or no longer exact");
    }
  }

  private void requireUnexpired(Record row) {
    Record validity =
        dsl.fetchOne(
            "SELECT lease_expires_at > clock_timestamp() AS unexpired "
                + "FROM "
                + TABLE
                + " WHERE target_namespace = ? AND control_plane_request_id = ?",
            requiredText(row, "target_namespace"),
            requiredText(row, "control_plane_request_id"));
    if (validity == null || !Boolean.TRUE.equals(validity.get("unexpired", Boolean.class))) {
      throw new StaleStartSessionOperatorAttemptClaimException(
          "Game Session owner attempt claim lease is expired and remains non-replayable");
    }
  }

  private static AttemptSnapshot snapshot(Record row) {
    return new AttemptSnapshot(
        requiredText(row, "target_namespace"),
        requiredText(row, "control_plane_request_id"),
        requiredUuid(row, "canonical_tenant_id"),
        requiredUuid(row, "owner_attempt_id"),
        requiredUuid(row, "owner_mutation_id"),
        requiredLong(row, "owner_fence"),
        requiredText(row, "phase_state"),
        requiredBytes(row, "post_authorization_execution_tuple"),
        optionalBytes(row, "account_redemption_projection"),
        requiredInstant(row, "created_at"),
        requiredInstant(row, "lease_expires_at"));
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "StartSession owner attempt requires a writable READ COMMITTED owner transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "StartSession owner attempt requires a writable READ COMMITTED owner transaction");
          }
          return null;
        });
    Record transaction =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (transaction == null
        || !"read committed".equals(transaction.get("isolation", String.class))
        || !"off".equals(transaction.get("read_only", String.class))) {
      throw new IllegalStateException(
          "StartSession owner attempt requires a writable READ COMMITTED owner transaction");
    }
  }

  private static long parsePositiveFence(String value) {
    try {
      long fence = Long.parseLong(value);
      if (fence <= 0L || !Long.toString(fence).equals(value)) {
        throw new NumberFormatException();
      }
      return fence;
    } catch (NumberFormatException malformed) {
      throw new IllegalArgumentException(
          "Account issuance fence is outside the signed owner range");
    }
  }

  private static UUID newNonNilUuid(String field) {
    UUID value = UUID.randomUUID();
    if (NIL_UUID.equals(value)) {
      throw new IllegalStateException("Random UUID source returned nil " + field);
    }
    return value;
  }

  private static String requiredText(Record row, String field) {
    String value = row == null ? null : row.get(field, String.class);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner attempt is missing " + field);
    }
    return value;
  }

  private static UUID requiredUuid(Record row, String field) {
    UUID value = optionalUuid(row, field);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner attempt is missing " + field);
    }
    return value;
  }

  private static UUID optionalUuid(Record row, String field) {
    return row == null ? null : row.get(field, UUID.class);
  }

  private static long requiredLong(Record row, String field) {
    Long value = optionalLong(row, field);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner attempt is missing " + field);
    }
    return value;
  }

  private static Long optionalLong(Record row, String field) {
    return row == null ? null : row.get(field, Long.class);
  }

  private static byte[] requiredBytes(Record row, String field) {
    byte[] value = optionalBytes(row, field);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner attempt is missing " + field);
    }
    return value;
  }

  private static byte[] optionalBytes(Record row, String field) {
    byte[] value = row == null ? null : row.get(field, byte[].class);
    return value == null ? null : value.clone();
  }

  private static Instant requiredInstant(Record row, String field) {
    java.time.OffsetDateTime value =
        row == null ? null : row.get(field, java.time.OffsetDateTime.class);
    if (value == null) {
      throw new IllegalStateException("Persisted Game Session owner attempt is missing " + field);
    }
    return value.toInstant();
  }

  public enum ReservationDisposition {
    CLAIM_CREATED,
    EXACT_REPLAY
  }

  /** Result distinguishes the unique newly minted claim from a replay snapshot. */
  public record ReservationResult(
      ReservationDisposition disposition, AttemptSnapshot snapshot, Optional<AttemptClaim> claim) {
    public ReservationResult {
      Objects.requireNonNull(disposition, "disposition is required");
      Objects.requireNonNull(snapshot, "snapshot is required");
      Objects.requireNonNull(claim, "claim wrapper is required");
      if ((disposition == ReservationDisposition.CLAIM_CREATED) != claim.isPresent()) {
        throw new IllegalArgumentException(
            "only a newly created owner attempt may return its claim");
      }
    }
  }

  /** One opaque-in-process capability returned only to the transaction that created the claim. */
  public record AttemptClaim(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID ownerAttemptId,
      UUID ownerMutationId,
      UUID claimOwnerId,
      long ownerFence) {
    public AttemptClaim {
      Objects.requireNonNull(targetNamespace, "targetNamespace is required");
      Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId is required");
      requireNonNil(ownerAttemptId, "ownerAttemptId");
      requireNonNil(ownerMutationId, "ownerMutationId");
      requireNonNil(claimOwnerId, "claimOwnerId");
      if (ownerFence <= 0L) {
        throw new IllegalArgumentException("ownerFence must be positive");
      }
    }
  }

  /** Immutable readback; byte-array accessors never expose the stored record buffer. */
  public static final class AttemptSnapshot {
    private final String targetNamespace;
    private final String controlPlaneRequestId;
    private final UUID canonicalTenantId;
    private final UUID ownerAttemptId;
    private final UUID ownerMutationId;
    private final long ownerFence;
    private final String phaseState;
    private final byte[] postAuthorizationExecutionTuple;
    private final byte[] accountRedemptionProjection;
    private final Instant createdAt;
    private final Instant leaseExpiresAt;

    private AttemptSnapshot(
        String targetNamespace,
        String controlPlaneRequestId,
        UUID canonicalTenantId,
        UUID ownerAttemptId,
        UUID ownerMutationId,
        long ownerFence,
        String phaseState,
        byte[] postAuthorizationExecutionTuple,
        byte[] accountRedemptionProjection,
        Instant createdAt,
        Instant leaseExpiresAt) {
      this.targetNamespace = targetNamespace;
      this.controlPlaneRequestId = controlPlaneRequestId;
      this.canonicalTenantId = canonicalTenantId;
      this.ownerAttemptId = ownerAttemptId;
      this.ownerMutationId = ownerMutationId;
      this.ownerFence = ownerFence;
      this.phaseState = phaseState;
      this.postAuthorizationExecutionTuple = postAuthorizationExecutionTuple.clone();
      this.accountRedemptionProjection =
          accountRedemptionProjection == null ? null : accountRedemptionProjection.clone();
      this.createdAt = createdAt;
      this.leaseExpiresAt = leaseExpiresAt;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public String controlPlaneRequestId() {
      return controlPlaneRequestId;
    }

    public UUID canonicalTenantId() {
      return canonicalTenantId;
    }

    public UUID ownerAttemptId() {
      return ownerAttemptId;
    }

    public UUID ownerMutationId() {
      return ownerMutationId;
    }

    public long ownerFence() {
      return ownerFence;
    }

    public String phaseState() {
      return phaseState;
    }

    public byte[] postAuthorizationExecutionTuple() {
      return postAuthorizationExecutionTuple.clone();
    }

    public byte[] accountRedemptionProjection() {
      return accountRedemptionProjection == null ? null : accountRedemptionProjection.clone();
    }

    public Instant createdAt() {
      return createdAt;
    }

    public Instant leaseExpiresAt() {
      return leaseExpiresAt;
    }
  }

  /** Typed, no-secret Account redemption output persisted only after exact tuple comparison. */
  public record AccountRedemptionProjection(
      String authorizationReferenceFingerprint,
      byte[] authorityEvidenceBundleBytes,
      UUID issuanceOperationId,
      long issuanceFence) {
    public AccountRedemptionProjection {
      if (authorizationReferenceFingerprint == null
          || !AUTHORIZATION_REFERENCE_FINGERPRINT
              .matcher(authorizationReferenceFingerprint)
              .matches()) {
        throw new IllegalArgumentException("Account redemption fingerprint is malformed");
      }
      authorityEvidenceBundleBytes =
          Objects.requireNonNull(
                  authorityEvidenceBundleBytes, "authority evidence bundle is required")
              .clone();
      Objects.requireNonNull(issuanceOperationId, "issuanceOperationId is required");
      if (issuanceOperationId.equals(NIL_UUID) || issuanceFence <= 0L) {
        throw new IllegalArgumentException(
            "Account redemption identity and fence must be positive");
      }
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(authorityEvidenceBundleBytes);
      if (!bundle.issuanceOperationId().equals(issuanceOperationId)
          || parsePositiveFence(bundle.issuanceFence()) != issuanceFence) {
        throw new IllegalArgumentException(
            "Account redemption response differs from its exact authority evidence bundle");
      }
    }

    @Override
    public byte[] authorityEvidenceBundleBytes() {
      return authorityEvidenceBundleBytes.clone();
    }

    public byte[] canonicalBytes() {
      StartSessionAuthorityEvidenceBundle bundle =
          StartSessionAuthorityEvidenceBundle.decode(authorityEvidenceBundleBytes);
      Map<String, Object> projection = new LinkedHashMap<>();
      projection.put("projectionSchemaId", "accountStartSessionRedemptionProjection");
      projection.put("projectionSchemaVersion", "1");
      projection.put("authorizationReferenceFingerprint", authorizationReferenceFingerprint);
      projection.put("authorityEvidenceBundle", bundle.jsonValue());
      projection.put("issuanceOperationId", issuanceOperationId.toString());
      projection.put("issuanceFence", issuanceFence);
      try {
        byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(projection));
        if (bytes.length > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
          throw new IllegalArgumentException(
              "Account redemption projection exceeds its byte limit");
        }
        return bytes;
      } catch (java.io.IOException exception) {
        throw new IllegalStateException(
            "could not encode Account redemption projection", exception);
      }
    }
  }

  public static final class StartSessionOperatorAttemptConflictException
      extends IllegalStateException {
    public StartSessionOperatorAttemptConflictException(String message) {
      super(message);
    }
  }

  public static final class StaleStartSessionOperatorAttemptClaimException
      extends IllegalStateException {
    public StaleStartSessionOperatorAttemptClaimException(String message) {
      super(message);
    }
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must not be nil");
    }
  }
}
