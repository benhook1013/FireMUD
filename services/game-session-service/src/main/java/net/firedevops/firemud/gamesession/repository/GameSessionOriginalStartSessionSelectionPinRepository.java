package net.firedevops.firemud.gamesession.repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable Game Session owner storage for the original first selection and descriptor pin. */
public final class GameSessionOriginalStartSessionSelectionPinRepository {
  private static final String TABLE = "game_session_original_start_session_selection_pin";

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final GameSessionStartSessionOperatorAttemptRepository attempts;
  private final TransactionTemplate readTransaction;

  public GameSessionOriginalStartSessionSelectionPinRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      GameSessionStartSessionOperatorAttemptRepository attempts,
      String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    this.attempts = Objects.requireNonNull(attempts, "original attempt repository is required");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    readTransaction = new TransactionTemplate(transactionManager);
    readTransaction.setReadOnly(true);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Appends the first pin only while the same original Account-attached attempt is pending and
   * unexpired. Exact retries return the first bytes; a changed request or response conflicts.
   */
  public StoredPin retainFirstInOwnerTransaction(Candidate candidate) {
    requireOwnerTransaction();
    Objects.requireNonNull(candidate, "selection pin candidate is required");
    requireIdentity(candidate.targetNamespace(), candidate.controlPlaneRequestId());
    if (!workloadNamespace.equals(candidate.targetNamespace())) {
      throw new IllegalArgumentException(
          "Selection pin differs from configured Game Session owner");
    }

    Record first = select(candidate.targetNamespace(), candidate.controlPlaneRequestId());
    if (first != null) {
      StoredPin retained = snapshot(first);
      requireSameCandidate(candidate, retained);
      return retained;
    }

    var tuple = StartSessionPostAuthorizationExecutionTuple.decode(candidate.originalTupleBytes());
    var continuation = attempts.beginEvidenceContinuation(tuple);
    var attempt = attempts.validateEvidenceContinuation(continuation);
    if (!attempt.ownerAttemptId().equals(candidate.ownerAttemptId())
        || !attempt.ownerMutationId().equals(candidate.ownerMutationId())
        || attempt.ownerFence() != candidate.ownerFence()
        || !Arrays.equals(attempt.postAuthorizationExecutionTuple(), candidate.originalTupleBytes())
        || !Arrays.equals(
            attempt.accountRedemptionProjection(), candidate.accountRedemptionProjection())) {
      throw conflict("Selection pin does not match the exact live original owner attempt");
    }

    dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (target_namespace, control_plane_request_id, canonical_tenant_id, "
            + "game_instance_uuid, launch_descriptor_id, owner_attempt_id, owner_mutation_id, "
            + "owner_fence, owner_lease_expires_at, original_post_authorization_tuple, "
            + "account_redemption_projection, initial_selection_request_bytes, "
            + "initial_selection_request_digest, initial_selection_response_bytes, "
            + "initial_selection_response_digest, descriptor_pin_request_bytes, "
            + "descriptor_pin_request_digest, descriptor_pin_response_bytes, "
            + "descriptor_pin_response_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (target_namespace, control_plane_request_id) DO NOTHING",
        candidate.targetNamespace(),
        candidate.controlPlaneRequestId(),
        candidate.canonicalTenantId(),
        candidate.gameInstanceUuid(),
        candidate.launchDescriptorId(),
        attempt.ownerAttemptId(),
        attempt.ownerMutationId(),
        attempt.ownerFence(),
        java.time.OffsetDateTime.ofInstant(attempt.leaseExpiresAt(), java.time.ZoneOffset.UTC),
        candidate.originalTupleBytes(),
        candidate.accountRedemptionProjection(),
        candidate.initialSelectionRequestBytes(),
        candidate.initialSelectionRequestDigest(),
        candidate.initialSelectionResponseBytes(),
        candidate.initialSelectionResponseDigest(),
        candidate.descriptorPinRequestBytes(),
        candidate.descriptorPinRequestDigest(),
        candidate.descriptorPinResponseBytes(),
        candidate.descriptorPinResponseDigest());

    Record row = select(candidate.targetNamespace(), candidate.controlPlaneRequestId());
    if (row == null) throw unavailable();
    StoredPin stored = snapshot(row);
    requireSameCandidate(candidate, stored);
    return stored;
  }

  /** Reads the already-retained first pin; it never creates or repairs one. */
  public Optional<StoredPin> readByOriginalOperation(
      String targetNamespace, String controlPlaneRequestId) {
    requireNoOwnerTransaction();
    requireIdentity(targetNamespace, controlPlaneRequestId);
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Selection pin read differs from configured Game Session owner");
    }
    Optional<StoredPin> result =
        Objects.requireNonNull(
            readTransaction.execute(
                ignored -> {
                  Record row = select(targetNamespace, controlPlaneRequestId);
                  return row == null ? Optional.empty() : Optional.of(snapshot(row));
                }),
            "Selection pin historical read returned no optional");
    return result;
  }

  /** Read-only pin lookup for composition with the attempt and launch in one owner snapshot. */
  public Optional<StoredPin> readByOriginalOperationInOwnerTransaction(
      String targetNamespace, String controlPlaneRequestId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Active Game Session historical owner snapshot required");
    }
    requireIdentity(targetNamespace, controlPlaneRequestId);
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Selection pin read differs from configured Game Session owner");
    }
    Record row = select(targetNamespace, controlPlaneRequestId);
    return row == null ? Optional.empty() : Optional.of(snapshot(row));
  }

  private Record select(String targetNamespace, String controlPlaneRequestId) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE target_namespace = ? AND control_plane_request_id = ?",
        targetNamespace,
        controlPlaneRequestId);
  }

  private StoredPin snapshot(Record row) {
    return new StoredPin(
        required(row, "target_namespace", String.class),
        required(row, "control_plane_request_id", String.class),
        required(row, "canonical_tenant_id", UUID.class),
        required(row, "game_instance_uuid", UUID.class),
        required(row, "launch_descriptor_id", String.class),
        required(row, "owner_attempt_id", UUID.class),
        required(row, "owner_mutation_id", UUID.class),
        required(row, "owner_fence", Long.class),
        required(row, "owner_lease_expires_at", java.time.OffsetDateTime.class).toInstant(),
        required(row, "original_post_authorization_tuple", byte[].class),
        required(row, "account_redemption_projection", byte[].class),
        required(row, "initial_selection_request_bytes", byte[].class),
        required(row, "initial_selection_request_digest", String.class),
        required(row, "initial_selection_response_bytes", byte[].class),
        required(row, "initial_selection_response_digest", String.class),
        required(row, "descriptor_pin_request_bytes", byte[].class),
        required(row, "descriptor_pin_request_digest", String.class),
        required(row, "descriptor_pin_response_bytes", byte[].class),
        required(row, "descriptor_pin_response_digest", String.class),
        required(row, "retained_at", java.time.OffsetDateTime.class).toInstant());
  }

  private static void requireSameCandidate(Candidate expected, StoredPin stored) {
    if (!expected.targetNamespace().equals(stored.targetNamespace())
        || !expected.controlPlaneRequestId().equals(stored.controlPlaneRequestId())
        || !expected.canonicalTenantId().equals(stored.canonicalTenantId())
        || !expected.gameInstanceUuid().equals(stored.gameInstanceUuid())
        || !expected.launchDescriptorId().equals(stored.launchDescriptorId())
        || !expected.ownerAttemptId().equals(stored.ownerAttemptId())
        || !expected.ownerMutationId().equals(stored.ownerMutationId())
        || expected.ownerFence() != stored.ownerFence()
        || !Arrays.equals(expected.originalTupleBytes(), stored.originalTupleBytes())
        || !Arrays.equals(
            expected.accountRedemptionProjection(), stored.accountRedemptionProjection())
        || !Arrays.equals(
            expected.initialSelectionRequestBytes(), stored.initialSelectionRequestBytes())
        || !expected.initialSelectionRequestDigest().equals(stored.initialSelectionRequestDigest())
        || !Arrays.equals(
            expected.initialSelectionResponseBytes(), stored.initialSelectionResponseBytes())
        || !expected
            .initialSelectionResponseDigest()
            .equals(stored.initialSelectionResponseDigest())
        || !Arrays.equals(expected.descriptorPinRequestBytes(), stored.descriptorPinRequestBytes())
        || !expected.descriptorPinRequestDigest().equals(stored.descriptorPinRequestDigest())
        || !Arrays.equals(
            expected.descriptorPinResponseBytes(), stored.descriptorPinResponseBytes())
        || !expected.descriptorPinResponseDigest().equals(stored.descriptorPinResponseDigest())) {
      throw conflict("Original StartSession selection pin conflicts with its first retained bytes");
    }
  }

  private static void requireIdentity(String targetNamespace, String requestId) {
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session namespace required");
    }
    if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
      throw new IllegalArgumentException(
          "Canonical original StartSession request identity required");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable caller-owned Game Session transaction required");
    }
  }

  private static void requireNoOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Historical selection pin read requires an independent snapshot");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) throw unavailable();
    return value;
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact original StartSession selection pin is unavailable");
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static byte[] copy(byte[] value, String field) {
    Objects.requireNonNull(value, field);
    if (value.length == 0) throw new IllegalArgumentException(field + " cannot be empty");
    return value.clone();
  }

  /** Exact external request/result bytes from the original Game Design reads. */
  public record Candidate(
      HistoricalOriginalStartSessionOwnerEvidence.Result evidence,
      UUID ownerMutationId,
      byte[] initialSelectionRequestBytes,
      byte[] initialSelectionResponseBytes,
      byte[] descriptorPinRequestBytes,
      byte[] descriptorPinResponseBytes) {
    public Candidate {
      Objects.requireNonNull(evidence, "complete historical original StartSession evidence");
      Objects.requireNonNull(ownerMutationId, "original owner mutation id");
      initialSelectionRequestBytes =
          copy(initialSelectionRequestBytes, "initial selection request");
      initialSelectionResponseBytes =
          copy(initialSelectionResponseBytes, "initial selection response");
      descriptorPinRequestBytes = copy(descriptorPinRequestBytes, "descriptor pin request");
      descriptorPinResponseBytes = copy(descriptorPinResponseBytes, "descriptor pin response");
      requireCanonicalWire(
          evidence.firstSelection().request(),
          net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
              .toRequest(evidence.firstSelection().request()),
          initialSelectionRequestBytes,
          "initial selection request");
      requireCanonicalWire(
          evidence.firstSelection(),
          net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec
              .toResponse(evidence.firstSelection()),
          initialSelectionResponseBytes,
          "initial selection response");
      var descriptorRequest = evidence.descriptorPin().associationRead().request();
      requireCanonicalWire(
          descriptorRequest,
          net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.toRequest(
              descriptorRequest),
          descriptorPinRequestBytes,
          "descriptor pin request");
      requireCanonicalWire(
          evidence.descriptorPin(),
          net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec.toResponse(
              descriptorRequest,
              evidence.descriptorPin().associationRead(),
              evidence.descriptorPin().outcome()),
          descriptorPinResponseBytes,
          "descriptor pin response");
    }

    public String targetNamespace() {
      return evidence.request().associationSelector().targetNamespace();
    }

    public String controlPlaneRequestId() {
      return evidence.originalTuple().controlPlaneRequestId();
    }

    public UUID canonicalTenantId() {
      return evidence.request().associationSelector().canonicalTenantId();
    }

    public UUID gameInstanceUuid() {
      return evidence.request().associationSelector().gameInstanceUuid();
    }

    public String launchDescriptorId() {
      return evidence.request().associationSelector().launchDescriptorId();
    }

    public UUID ownerAttemptId() {
      return evidence.ownerAttemptId();
    }

    public UUID ownerMutationId() {
      return ownerMutationId;
    }

    public long ownerFence() {
      return evidence.ownerFence();
    }

    public byte[] originalTupleBytes() {
      return evidence.originalTuple().canonicalBytes();
    }

    public byte[] accountRedemptionProjection() {
      return evidence.accountRedemptionProjection();
    }

    public String initialSelectionRequestDigest() {
      return digest(initialSelectionRequestBytes);
    }

    public String initialSelectionResponseDigest() {
      return digest(initialSelectionResponseBytes);
    }

    public String descriptorPinRequestDigest() {
      return digest(descriptorPinRequestBytes);
    }

    public String descriptorPinResponseDigest() {
      return digest(descriptorPinResponseBytes);
    }

    @Override
    public byte[] initialSelectionRequestBytes() {
      return initialSelectionRequestBytes.clone();
    }

    @Override
    public byte[] initialSelectionResponseBytes() {
      return initialSelectionResponseBytes.clone();
    }

    @Override
    public byte[] descriptorPinRequestBytes() {
      return descriptorPinRequestBytes.clone();
    }

    @Override
    public byte[] descriptorPinResponseBytes() {
      return descriptorPinResponseBytes.clone();
    }
  }

  private static void requireCanonicalWire(
      Object evidence, com.google.protobuf.Message canonical, byte[] bytes, String field) {
    Objects.requireNonNull(evidence, field + " evidence");
    if (!Arrays.equals(canonical.toByteArray(), bytes)) {
      throw new IllegalArgumentException(
          field + " bytes differ from the closed canonical evidence");
    }
  }

  public record StoredPin(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      UUID gameInstanceUuid,
      String launchDescriptorId,
      UUID ownerAttemptId,
      UUID ownerMutationId,
      long ownerFence,
      Instant ownerLeaseExpiresAt,
      byte[] originalTupleBytes,
      byte[] accountRedemptionProjection,
      byte[] initialSelectionRequestBytes,
      String initialSelectionRequestDigest,
      byte[] initialSelectionResponseBytes,
      String initialSelectionResponseDigest,
      byte[] descriptorPinRequestBytes,
      String descriptorPinRequestDigest,
      byte[] descriptorPinResponseBytes,
      String descriptorPinResponseDigest,
      Instant retainedAt) {
    public StoredPin {
      originalTupleBytes = originalTupleBytes.clone();
      accountRedemptionProjection = accountRedemptionProjection.clone();
      initialSelectionRequestBytes = initialSelectionRequestBytes.clone();
      initialSelectionResponseBytes = initialSelectionResponseBytes.clone();
      descriptorPinRequestBytes = descriptorPinRequestBytes.clone();
      descriptorPinResponseBytes = descriptorPinResponseBytes.clone();
    }

    @Override
    public byte[] originalTupleBytes() {
      return originalTupleBytes.clone();
    }

    @Override
    public byte[] accountRedemptionProjection() {
      return accountRedemptionProjection.clone();
    }

    @Override
    public byte[] initialSelectionRequestBytes() {
      return initialSelectionRequestBytes.clone();
    }

    @Override
    public byte[] initialSelectionResponseBytes() {
      return initialSelectionResponseBytes.clone();
    }

    @Override
    public byte[] descriptorPinRequestBytes() {
      return descriptorPinRequestBytes.clone();
    }

    @Override
    public byte[] descriptorPinResponseBytes() {
      return descriptorPinResponseBytes.clone();
    }
  }
}
