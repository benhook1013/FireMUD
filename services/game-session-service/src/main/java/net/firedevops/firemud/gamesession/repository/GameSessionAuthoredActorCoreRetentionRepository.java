package net.firedevops.firemud.gamesession.repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered, append-only Game Session retention for the exact public Game Design authored-core
 * result belonging to one original StartSession launch and owner attempt.
 *
 * <p>This repository persists only the already-authenticated public result and its exact owner
 * bindings. It does not authenticate Game Design, select a policy, allocate an actor, or grant
 * runtime authority.
 */
public final class GameSessionAuthoredActorCoreRetentionRepository {
  private static final String TABLE = "gs_original_start_session_authored_actor_core_retention";

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final TransactionTemplate readTransaction;

  public GameSessionAuthoredActorCoreRetentionRepository(
      DSLContext dsl, PlatformTransactionManager transactionManager, String workloadNamespace) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    readTransaction = new TransactionTemplate(transactionManager);
    readTransaction.setReadOnly(true);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Reads the immutable row by original operation identity in an independent owner snapshot. */
  public Optional<StoredSource> readByOriginalOperation(
      String targetNamespace, String controlPlaneRequestId) {
    requireNoOwnerTransaction();
    requireIdentity(targetNamespace, controlPlaneRequestId);
    if (!workloadNamespace.equals(targetNamespace)) {
      throw new IllegalArgumentException(
          "Authored-core retention read differs from configured Game Session workload namespace");
    }
    Optional<StoredSource> result =
        readTransaction.execute(
            status -> {
              Record row = select(targetNamespace, controlPlaneRequestId);
              return row == null ? Optional.empty() : Optional.of(snapshot(row));
            });
    return Objects.requireNonNull(result, "Authored-core retention read returned no result");
  }

  /**
   * Appends or exact-replays one result inside the caller's writable owner transaction. The caller
   * must have locked and revalidated the original live attempt in this same transaction.
   */
  public StoredSource retainInOwnerTransaction(Candidate candidate) {
    requireOwnerTransaction();
    Objects.requireNonNull(candidate, "retention candidate");
    requireIdentity(candidate.targetNamespace(), candidate.controlPlaneRequestId());
    if (!workloadNamespace.equals(candidate.targetNamespace())) {
      throw new IllegalArgumentException(
          "Authored-core retention namespace differs from configured Game Session owner");
    }

    dsl.execute(
        "INSERT INTO "
            + TABLE
            + " (target_namespace, control_plane_request_id, canonical_tenant_id, "
            + "game_instance_uuid, owner_attempt_id, owner_mutation_id, owner_fence, "
            + "owner_lease_expires_at, original_post_authorization_tuple, "
            + "account_redemption_projection, account_redemption_projection_digest, "
            + "canonical_version_id, selected_commit_id, publish_workflow_id, association_digest, "
            + "world_source_operation_id, world_source_evidence_digest, world_slug, "
            + "launch_descriptor_id, descriptor_request_digest, descriptor_result_digest, "
            + "release_attestation_evidence_digest, source_read_request_bytes, "
            + "canonical_source_result_bytes, source_result_digest) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (target_namespace, control_plane_request_id) DO NOTHING",
        candidate.targetNamespace(),
        candidate.controlPlaneRequestId(),
        candidate.canonicalTenantId(),
        candidate.gameInstanceUuid(),
        candidate.ownerAttemptId(),
        candidate.ownerMutationId(),
        candidate.ownerFence(),
        candidate.ownerLeaseExpiresAt(),
        candidate.originalPostAuthorizationTuple(),
        candidate.accountRedemptionProjection(),
        candidate.accountRedemptionProjectionDigest(),
        candidate.canonicalVersionId(),
        candidate.selectedCommitId(),
        candidate.publishWorkflowId(),
        candidate.associationDigest(),
        candidate.worldSourceOperationId(),
        candidate.worldSourceEvidenceDigest(),
        candidate.worldSlug(),
        candidate.launchDescriptorId(),
        candidate.descriptorRequestDigest(),
        candidate.descriptorResultDigest(),
        candidate.releaseAttestationEvidenceDigest(),
        candidate.sourceReadRequestBytes(),
        candidate.canonicalSourceResultBytes(),
        candidate.sourceResultDigest());

    Record row = select(candidate.targetNamespace(), candidate.controlPlaneRequestId());
    if (row == null) {
      throw new IllegalStateException("Authored-core retention row did not read back");
    }
    StoredSource stored = snapshot(row);
    requireExactCandidate(candidate, stored.candidate());
    return stored;
  }

  private Record select(String targetNamespace, String controlPlaneRequestId) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE target_namespace = ? AND control_plane_request_id = ?",
        targetNamespace,
        controlPlaneRequestId);
  }

  private StoredSource snapshot(Record row) {
    Candidate candidate =
        new Candidate(
            required(row, "target_namespace", String.class),
            required(row, "control_plane_request_id", String.class),
            required(row, "canonical_tenant_id", UUID.class),
            required(row, "game_instance_uuid", UUID.class),
            required(row, "owner_attempt_id", UUID.class),
            required(row, "owner_mutation_id", UUID.class),
            required(row, "owner_fence", Long.class),
            required(row, "owner_lease_expires_at", java.time.OffsetDateTime.class).toInstant(),
            required(row, "original_post_authorization_tuple", byte[].class),
            required(row, "account_redemption_projection", byte[].class),
            required(row, "account_redemption_projection_digest", String.class),
            required(row, "canonical_version_id", UUID.class),
            required(row, "selected_commit_id", UUID.class),
            required(row, "publish_workflow_id", String.class),
            required(row, "association_digest", String.class),
            required(row, "world_source_operation_id", UUID.class),
            required(row, "world_source_evidence_digest", String.class),
            required(row, "world_slug", String.class),
            required(row, "launch_descriptor_id", String.class),
            required(row, "descriptor_request_digest", String.class),
            required(row, "descriptor_result_digest", String.class),
            required(row, "release_attestation_evidence_digest", String.class),
            required(row, "source_read_request_bytes", byte[].class),
            required(row, "canonical_source_result_bytes", byte[].class),
            required(row, "source_result_digest", String.class));
    Instant retainedAt = required(row, "retained_at", java.time.OffsetDateTime.class).toInstant();
    return new StoredSource(candidate, retainedAt);
  }

  private static void requireExactCandidate(Candidate expected, Candidate stored) {
    if (!expected.targetNamespace().equals(stored.targetNamespace())
        || !expected.controlPlaneRequestId().equals(stored.controlPlaneRequestId())
        || !expected.canonicalTenantId().equals(stored.canonicalTenantId())
        || !expected.gameInstanceUuid().equals(stored.gameInstanceUuid())
        || !expected.ownerAttemptId().equals(stored.ownerAttemptId())
        || !expected.ownerMutationId().equals(stored.ownerMutationId())
        || expected.ownerFence() != stored.ownerFence()
        || !expected.ownerLeaseExpiresAt().equals(stored.ownerLeaseExpiresAt())
        || !arraysEqual(
            expected.originalPostAuthorizationTuple(), stored.originalPostAuthorizationTuple())
        || !arraysEqual(
            expected.accountRedemptionProjection(), stored.accountRedemptionProjection())
        || !expected
            .accountRedemptionProjectionDigest()
            .equals(stored.accountRedemptionProjectionDigest())
        || !expected.canonicalVersionId().equals(stored.canonicalVersionId())
        || !expected.selectedCommitId().equals(stored.selectedCommitId())
        || !expected.publishWorkflowId().equals(stored.publishWorkflowId())
        || !expected.associationDigest().equals(stored.associationDigest())
        || !expected.worldSourceOperationId().equals(stored.worldSourceOperationId())
        || !expected.worldSourceEvidenceDigest().equals(stored.worldSourceEvidenceDigest())
        || !expected.worldSlug().equals(stored.worldSlug())
        || !expected.launchDescriptorId().equals(stored.launchDescriptorId())
        || !expected.descriptorRequestDigest().equals(stored.descriptorRequestDigest())
        || !expected.descriptorResultDigest().equals(stored.descriptorResultDigest())
        || !expected
            .releaseAttestationEvidenceDigest()
            .equals(stored.releaseAttestationEvidenceDigest())
        || !arraysEqual(expected.sourceReadRequestBytes(), stored.sourceReadRequestBytes())
        || !arraysEqual(expected.canonicalSourceResultBytes(), stored.canonicalSourceResultBytes())
        || !expected.sourceResultDigest().equals(stored.sourceResultDigest())) {
      throw new IllegalStateException(
          "Original StartSession authored-core retention conflicts with its first exact result");
    }
  }

  private void requireIdentity(String targetNamespace, String controlPlaneRequestId) {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session namespace required");
    }
    if (controlPlaneRequestId == null
        || controlPlaneRequestId.isBlank()
        || controlPlaneRequestId.length() > 128) {
      throw new IllegalArgumentException(
          "Canonical original StartSession request identity required");
    }
  }

  private static void requireNoOwnerTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Authored-core retention read requires an independent owner snapshot");
    }
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Authored-core retention requires a writable caller-owned owner transaction");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row == null ? null : row.get(field, type);
    if (value == null) {
      throw new IllegalStateException("Retained authored-core result is missing " + field);
    }
    return value;
  }

  private static boolean arraysEqual(byte[] left, byte[] right) {
    return MessageDigest.isEqual(left, right);
  }

  /**
   * One exact append candidate, including the unchanged canonical result returned by Game Design.
   */
  public record Candidate(
      String targetNamespace,
      String controlPlaneRequestId,
      UUID canonicalTenantId,
      UUID gameInstanceUuid,
      UUID ownerAttemptId,
      UUID ownerMutationId,
      long ownerFence,
      Instant ownerLeaseExpiresAt,
      byte[] originalPostAuthorizationTuple,
      byte[] accountRedemptionProjection,
      String accountRedemptionProjectionDigest,
      UUID canonicalVersionId,
      UUID selectedCommitId,
      String publishWorkflowId,
      String associationDigest,
      UUID worldSourceOperationId,
      String worldSourceEvidenceDigest,
      String worldSlug,
      String launchDescriptorId,
      String descriptorRequestDigest,
      String descriptorResultDigest,
      String releaseAttestationEvidenceDigest,
      byte[] sourceReadRequestBytes,
      byte[] canonicalSourceResultBytes,
      String sourceResultDigest) {
    public Candidate {
      Objects.requireNonNull(targetNamespace, "targetNamespace");
      Objects.requireNonNull(controlPlaneRequestId, "controlPlaneRequestId");
      Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(gameInstanceUuid, "gameInstanceUuid");
      Objects.requireNonNull(ownerAttemptId, "ownerAttemptId");
      Objects.requireNonNull(ownerMutationId, "ownerMutationId");
      Objects.requireNonNull(ownerLeaseExpiresAt, "ownerLeaseExpiresAt");
      Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
      Objects.requireNonNull(selectedCommitId, "selectedCommitId");
      Objects.requireNonNull(publishWorkflowId, "publishWorkflowId");
      Objects.requireNonNull(associationDigest, "associationDigest");
      Objects.requireNonNull(worldSourceOperationId, "worldSourceOperationId");
      Objects.requireNonNull(worldSourceEvidenceDigest, "worldSourceEvidenceDigest");
      Objects.requireNonNull(worldSlug, "worldSlug");
      Objects.requireNonNull(launchDescriptorId, "launchDescriptorId");
      Objects.requireNonNull(descriptorRequestDigest, "descriptorRequestDigest");
      Objects.requireNonNull(descriptorResultDigest, "descriptorResultDigest");
      Objects.requireNonNull(releaseAttestationEvidenceDigest, "releaseAttestationEvidenceDigest");
      Objects.requireNonNull(
          accountRedemptionProjectionDigest, "accountRedemptionProjectionDigest");
      Objects.requireNonNull(sourceResultDigest, "sourceResultDigest");
      originalPostAuthorizationTuple =
          copyBounded(originalPostAuthorizationTuple, 262_144, "original tuple");
      accountRedemptionProjection =
          copyBounded(accountRedemptionProjection, 262_144, "Account projection");
      sourceReadRequestBytes = copyBounded(sourceReadRequestBytes, 524_288, "source read request");
      canonicalSourceResultBytes =
          copyBounded(
              canonicalSourceResultBytes,
              AuthoredInitialActorCoreReadEvidence.MAX_RESULT_BYTES,
              "canonical source result");
      if (ownerFence <= 0L) {
        throw new IllegalArgumentException("ownerFence must be positive");
      }
      StartSessionPostAuthorizationExecutionTuple tuple =
          StartSessionPostAuthorizationExecutionTuple.decode(originalPostAuthorizationTuple);
      if (!tuple.controlPlaneRequestId().equals(controlPlaneRequestId)
          || !tuple
              .preAuthorizationTuple()
              .action()
              .scope()
              .targetNamespace()
              .equals(targetNamespace)
          || !tuple.preAuthorizationTuple().action().scope().tenantId().equals(canonicalTenantId)) {
        throw new IllegalArgumentException("Retention identity differs from the original tuple");
      }
      if (!sha256Hex(accountRedemptionProjection).equals(accountRedemptionProjectionDigest)) {
        throw new IllegalArgumentException("Account projection digest differs from exact bytes");
      }
      if (!sha256(canonicalSourceResultBytes).equals(sourceResultDigest)) {
        throw new IllegalArgumentException(
            "Source result digest differs from exact canonical bytes");
      }
    }

    @Override
    public byte[] originalPostAuthorizationTuple() {
      return originalPostAuthorizationTuple.clone();
    }

    @Override
    public byte[] accountRedemptionProjection() {
      return accountRedemptionProjection.clone();
    }

    @Override
    public byte[] sourceReadRequestBytes() {
      return sourceReadRequestBytes.clone();
    }

    @Override
    public byte[] canonicalSourceResultBytes() {
      return canonicalSourceResultBytes.clone();
    }
  }

  /** Immutable independent owner readback. */
  public record StoredSource(Candidate candidate, Instant retainedAt) {
    public StoredSource {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(retainedAt, "retainedAt");
    }
  }

  private static byte[] copyBounded(byte[] value, int maximum, String label) {
    Objects.requireNonNull(value, label);
    if (value.length == 0 || value.length > maximum) {
      throw new IllegalArgumentException("Complete bounded " + label + " bytes required");
    }
    return value.clone();
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
