package net.firedevops.firemud.entitymanagement.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionBinding;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterSnapshot;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered owner-local retention source. Every operation requires a writable owner transaction.
 *
 * <p>The integrating authenticated owner adapter must validate acquire/release authority. This
 * repository does not authenticate Java callers, accept terminal booleans or provide disposal.
 * Authenticated original-operation carriers bind immutable snapshot receipts atomically. Current
 * source evidence cannot attest complete consumer/restore closure, so snapshots remain protected
 * even after locally recorded non-original references are released.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "DSLContext is the owner transaction collaborator")
public final class CanonicalGameplayRosterRetentionRepository {
  private final DSLContext dsl;

  public CanonicalGameplayRosterRetentionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  public UUID reserveAdmission(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    requireOwnerTransaction();
    byte[] binding = admissionBinding(request);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_admissions (operation_uuid, admission_uuid, request_binding) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
        request.requestUuid(),
        UUID.randomUUID(),
        binding);
    Record original =
        dsl.fetchSingle(
            "SELECT admission_uuid, request_binding, admission_state FROM entity_canonical_roster_admissions WHERE operation_uuid = ? FOR UPDATE",
            request.requestUuid());
    if (!Arrays.equals(binding, original.get("request_binding", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_REQUEST_CONFLICT");
    }
    if (!"OPEN".equals(original.get("admission_state", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_DISPOSED");
    }
    return original.get("admission_uuid", UUID.class);
  }

  public void requireAdmission(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request,
      UUID nonce) {
    requireOwnerTransaction();
    Record original =
        dsl.fetchOne(
            "SELECT request_binding FROM entity_canonical_roster_admissions WHERE operation_uuid = ? "
                + "AND admission_uuid = ? AND admission_state = 'OPEN' FOR UPDATE",
            request.requestUuid(),
            nonce);
    if (original == null
        || !Arrays.equals(
            admissionBinding(request), original.get("request_binding", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
  }

  public void bindAdmission(UUID operation, UUID nonce, UUID snapshot) {
    requireOwnerTransaction();
    int changed =
        dsl.execute(
            "UPDATE entity_canonical_roster_admissions SET snapshot_uuid = ? WHERE operation_uuid = ? AND admission_uuid = ? "
                + "AND admission_state = 'OPEN' AND (snapshot_uuid IS NULL OR snapshot_uuid = ?)",
            snapshot,
            operation,
            nonce,
            snapshot);
    if (changed != 1) {
      throw new IllegalStateException("CANONICAL_ROSTER_LOCAL_ADMISSION_FENCE_CLOSED");
    }
  }

  private static byte[] admissionBinding(
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterReadRequest request) {
    return net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRequest.newBuilder()
        .setRequestUuid(request.requestUuid().toString())
        .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
        .setExpectedTarget(request.expectedTarget().toWire())
        .setPlayerExecutionContext(
            net.firedevops.firemud.shared.v1.PlayerExecutionContext.newBuilder()
                .setSessionId(request.playerExecutionContext().sessionUuid().toString()))
        .build()
        .toByteArray();
  }

  /** Serializes recovery of the same original operation before any new snapshot capture. */
  public Optional<CanonicalGameplayRosterResponse> originalReceipt(
      GetCanonicalGameplayRosterReferenceResponse owner) {
    requireOwnerTransaction();
    var request = owner.getRequest();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, owner);
    UUID operation = CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid());
    dsl.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "entity-roster-original:" + operation);
    Record original =
        dsl.fetchOne(
            "SELECT owner_request, owner_fence, owner_target_fence_uuid, canonical_roster_receipt "
                + "FROM entity_canonical_roster_original_selections WHERE operation_uuid = ? FOR UPDATE",
            operation);
    if (original == null) {
      return Optional.empty();
    }
    if (!owner
            .getTargetFenceUuid()
            .equals(original.get("owner_target_fence_uuid", UUID.class).toString())
        || owner.getOwnerFence() != original.get("owner_fence", Long.class)
        || !Arrays.equals(request.toByteArray(), original.get("owner_request", byte[].class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_REQUEST_CONFLICT");
    }
    try {
      var receipt =
          CanonicalGameplayRosterResponse.parseFrom(
              original.get("canonical_roster_receipt", byte[].class));
      CanonicalGameplayRosterReferenceEvidence.response(
          request,
          owner.getOwnerFence(),
          java.util.UUID.fromString(owner.getTargetFenceUuid()),
          receipt);
      if (!owner.getCanonicalRosterReceipt().isEmpty()
          && !owner.getCanonicalRosterReceipt().equals(receipt.toByteString())) {
        throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_CONFLICT");
      }
      return Optional.of(receipt);
    } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RECEIPT_INVALID", invalid);
    }
  }

  /** Must join snapshot capture's transaction; a failed reference bind rolls the capture back. */
  public void bindOriginal(
      GetCanonicalGameplayRosterReferenceResponse owner, CanonicalGameplayRosterSnapshot snapshot) {
    requireOwnerTransaction();
    var request = owner.getRequest();
    CanonicalGameplayRosterReferenceEvidence.requireResponse(request, owner);
    CanonicalGameplayRosterReferenceEvidence.response(
        request,
        owner.getOwnerFence(),
        java.util.UUID.fromString(owner.getTargetFenceUuid()),
        snapshot.toWire());
    UUID operation = CanonicalGameplayRosterReferenceEvidence.uuid(request.getOperationUuid());
    var binding =
        new CanonicalGameplayRosterRetentionBinding(
            snapshot.canonicalAccountUuid(),
            new net.firedevops.firemud.entitymanagement.service
                .CanonicalGameplayRosterSnapshotReference(
                snapshot.snapshotUuid(), snapshot.snapshotDigest()),
            snapshot.target());
    acquire(binding, operation, operation, ReferenceKind.ORIGINAL_SELECTION);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_original_selections "
            + "(operation_uuid, owner_request, owner_fence, owner_target_fence_uuid, snapshot_uuid, snapshot_digest, canonical_roster_receipt) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        operation,
        request.toByteArray(),
        owner.getOwnerFence(),
        UUID.fromString(owner.getTargetFenceUuid()),
        snapshot.snapshotUuid(),
        snapshot.snapshotDigest(),
        snapshot.toWire().toByteArray());
  }

  public record Candidate(
      CanonicalGameplayRosterRetentionBinding binding,
      net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest ownerRequest,
      long localEpoch,
      String lifecycleState,
      String proofDigest) {
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
        ownerRequest() {
      return ownerRequest;
    }
  }

  /** A bounded UUID page permits later sweeps to pass protected/unavailable owners. */
  public java.util.List<Candidate> candidates(UUID afterSnapshot, int limit) {
    requireOwnerTransaction();
    if (limit <= 0) {
      throw new IllegalArgumentException("Bounded roster candidate page required");
    }
    return dsl.fetch(
            "SELECT DISTINCT ON (o.snapshot_uuid) o.snapshot_uuid, o.snapshot_digest, o.owner_request, "
                + "l.local_epoch, l.lifecycle_state, l.owner_retirement_proof_digest FROM entity_canonical_roster_original_selections o "
                + "JOIN entity_canonical_roster_retention_lifecycle l USING (snapshot_uuid, snapshot_digest) "
                + "WHERE (?::uuid IS NULL OR o.snapshot_uuid > ?::uuid) ORDER BY o.snapshot_uuid, o.operation_uuid LIMIT ?",
            afterSnapshot,
            afterSnapshot,
            limit)
        .map(
            row -> {
              try {
                var request =
                    net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                        .parseFrom(row.get("owner_request", byte[].class));
                CanonicalGameplayRosterReferenceEvidence.requireRequest(request);
                var target =
                    net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterTarget
                        .fromWire(CanonicalGameplayRosterReferenceEvidence.target(request));
                var binding =
                    new CanonicalGameplayRosterRetentionBinding(
                        UUID.fromString(request.getCanonicalAccountUuid()),
                        new net.firedevops.firemud.entitymanagement.service
                            .CanonicalGameplayRosterSnapshotReference(
                            row.get("snapshot_uuid", UUID.class),
                            row.get("snapshot_digest", String.class)),
                        target);
                return new Candidate(
                    binding,
                    request,
                    row.get("local_epoch", Long.class),
                    row.get("lifecycle_state", String.class),
                    row.get("owner_retirement_proof_digest", String.class));
              } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                throw new IllegalStateException(
                    "CANONICAL_ROSTER_ORIGINAL_REQUEST_INVALID", invalid);
              }
            });
  }

  /** Authenticated immutable GS target-seal evidence is observed on Entity's independent clock. */
  public boolean observeRetirement(
      Candidate candidate,
      GetCanonicalGameplayRosterReferenceResponse proof,
      net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy
          policy) {
    CanonicalGameplayRosterReferenceEvidence.requireResponse(candidate.ownerRequest(), proof);
    CanonicalGameplayRosterReferenceEvidence.requireRetirement(proof);
    var receipt = CanonicalGameplayRosterReferenceEvidence.receipt(proof);
    if (!candidate.binding().snapshot().snapshotUuid().toString().equals(receipt.getSnapshotUuid())
        || !candidate.binding().snapshot().snapshotDigest().equals(receipt.getSnapshotDigest())) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETIREMENT_SNAPSHOT_MISMATCH");
    }
    lock(candidate.binding());
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT * FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            candidate.binding().snapshot().snapshotUuid());
    if (candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)) {
      return false;
    }
    if (!"PROTECTED".equals(lifecycle.get("lifecycle_state", String.class))) {
      return proof
              .getTargetFenceUuid()
              .equals(lifecycle.get("owner_target_fence_uuid", UUID.class).toString())
          && proof
              .getRetirementUuid()
              .equals(lifecycle.get("owner_retirement_uuid", UUID.class).toString());
    }
    var originals =
        dsl.fetch(
            "SELECT o.operation_uuid, o.owner_fence, o.owner_target_fence_uuid, o.owner_request "
                + "FROM entity_canonical_roster_original_selections o WHERE snapshot_uuid = ? FOR UPDATE",
            candidate.binding().snapshot().snapshotUuid());
    for (Record original : originals) {
      if (original.get("owner_fence", Long.class) != proof.getOwnerFence()
          || !original
              .get("owner_target_fence_uuid", UUID.class)
              .toString()
              .equals(proof.getTargetFenceUuid())) {
        return false;
      }
      try {
        var request =
            net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
                .parseFrom(original.get("owner_request", byte[].class));
        if (!request.getCanonicalAccountUuid().equals(proof.getRequest().getCanonicalAccountUuid())
            || !request.getTargetNamespace().equals(proof.getRequest().getTargetNamespace())
            || !request.getCanonicalTarget().equals(proof.getRequest().getCanonicalTarget())) {
          return false;
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
        throw new IllegalStateException(invalid);
      }
    }
    // Preserve unknown local recovery/hold references. Only original references attested by the
    // exact sealed GS target fence are released here, in the same local transaction.
    for (Record original : originals) {
      UUID reference = original.get("operation_uuid", UUID.class);
      UUID release =
          UUID.nameUUIDFromBytes(
              (proof.getRetirementUuid() + ":" + reference)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      dsl.execute(
          "INSERT INTO entity_canonical_roster_retention_releases "
              + "(release_operation_uuid, reference_uuid, snapshot_uuid, snapshot_digest) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
          release,
          reference,
          candidate.binding().snapshot().snapshotUuid(),
          candidate.binding().snapshot().snapshotDigest());
    }
    dsl.execute(
        "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'RETIRED', local_epoch = local_epoch + 1, "
            + "owner_target_fence_uuid = ?, owner_retirement_uuid = ?, owner_retirement_proof_digest = ?, owner_retirement_proof = ?, "
            + "retirement_observed_at = clock_timestamp(), minimum_retention_millis = ? WHERE snapshot_uuid = ? AND lifecycle_state = 'PROTECTED'",
        UUID.fromString(proof.getTargetFenceUuid()),
        UUID.fromString(proof.getRetirementUuid()),
        proof.getRetirementProofDigest(),
        proof.toByteArray(),
        policy.minimumRetention().toMillis(),
        candidate.binding().snapshot().snapshotUuid());
    return true;
  }

  /** All deletes are guarded by exact persisted owner proof, local epoch and current references. */
  public boolean dispose(Candidate candidate) {
    requireOwnerTransaction();
    // The same admission-before-snapshot lock order fences delayed duplicate handlers.
    dsl.fetch(
        "SELECT admission_uuid FROM entity_canonical_roster_admissions WHERE snapshot_uuid = ? ORDER BY operation_uuid FOR UPDATE",
        candidate.binding().snapshot().snapshotUuid());
    lock(candidate.binding());
    UUID snapshot = candidate.binding().snapshot().snapshotUuid();
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT *, retirement_observed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ? FOR UPDATE",
            snapshot);
    if (!"RETIRED".equals(lifecycle.get("lifecycle_state", String.class))
        || candidate.localEpoch() != lifecycle.get("local_epoch", Long.class)
        || !Objects.equals(
            candidate.proofDigest(), lifecycle.get("owner_retirement_proof_digest", String.class))
        || !Boolean.TRUE.equals(lifecycle.get("horizon_passed", Boolean.class))
        || unreleasedCount(snapshot) != 0L) {
      return false;
    }
    long disposalEpoch = Math.addExact(candidate.localEpoch(), 1L);
    dsl.execute(
        "UPDATE entity_canonical_roster_retention_lifecycle SET lifecycle_state = 'DISPOSING', local_epoch = ? WHERE snapshot_uuid = ?",
        disposalEpoch,
        snapshot);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_disposal_receipts "
            + "(snapshot_uuid, snapshot_digest, owner_target_fence_uuid, owner_retirement_uuid, owner_retirement_proof_digest, local_epoch, minimum_retention_millis) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        snapshot,
        candidate.binding().snapshot().snapshotDigest(),
        lifecycle.get("owner_target_fence_uuid", UUID.class),
        lifecycle.get("owner_retirement_uuid", UUID.class),
        candidate.proofDigest(),
        disposalEpoch,
        lifecycle.get("minimum_retention_millis", Long.class));
    dsl.execute(
        "UPDATE entity_canonical_roster_admissions SET admission_state = 'DISPOSED', disposed_at = clock_timestamp(), minimum_retention_millis = ? "
            + "WHERE snapshot_uuid = ? AND admission_state = 'OPEN'",
        lifecycle.get("minimum_retention_millis", Long.class),
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_gameplay_roster_snapshot_actors WHERE snapshot_uuid = ?",
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_original_selections WHERE snapshot_uuid = ?",
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_retention_references WHERE snapshot_uuid = ?",
        snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_retention_releases WHERE snapshot_uuid = ?", snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_gameplay_roster_snapshots WHERE snapshot_uuid = ?", snapshot);
    dsl.execute(
        "DELETE FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
        snapshot);
    return true;
  }

  public int purgeDisposedTombstones(int limit) {
    requireOwnerTransaction();
    if (limit <= 0) {
      throw new IllegalArgumentException("Bounded tombstone purge required");
    }
    dsl.execute(
        "DELETE FROM entity_canonical_roster_admissions WHERE operation_uuid IN (SELECT operation_uuid FROM entity_canonical_roster_admissions "
            + "WHERE admission_state = 'DISPOSED' AND disposed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() ORDER BY operation_uuid LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
    return dsl.execute(
        "DELETE FROM entity_canonical_roster_disposal_receipts WHERE snapshot_uuid IN (SELECT snapshot_uuid FROM entity_canonical_roster_disposal_receipts d "
            + "WHERE disposed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() "
            + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_admissions a WHERE a.snapshot_uuid = d.snapshot_uuid) "
            + "ORDER BY snapshot_uuid LIMIT ? FOR UPDATE SKIP LOCKED)",
        limit);
  }

  private long unreleasedCount(UUID snapshot) {
    return dsl.fetchSingle(
            "SELECT count(*) FROM entity_canonical_roster_retention_references r WHERE r.snapshot_uuid = ? "
                + "AND NOT EXISTS (SELECT 1 FROM entity_canonical_roster_retention_releases x WHERE x.reference_uuid = r.reference_uuid)",
            snapshot)
        .get(0, Long.class);
  }

  public enum ReferenceKind {
    ORIGINAL_SELECTION,
    RECOVERY,
    HOLD
  }

  /** An exact acquire operation remains replayable after its matching release. */
  public void acquire(
      CanonicalGameplayRosterRetentionBinding binding,
      UUID operationUuid,
      UUID referenceUuid,
      ReferenceKind kind) {
    requireUuid(operationUuid, "operationUuid");
    requireUuid(referenceUuid, "referenceUuid");
    Objects.requireNonNull(kind, "kind");
    lock(binding);
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retention_references "
            + "(reference_uuid, acquire_operation_uuid, snapshot_uuid, snapshot_digest, reference_kind) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        referenceUuid,
        operationUuid,
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest(),
        kind.name());
    Record retained =
        dsl.fetchOne(
            "SELECT acquire_operation_uuid, snapshot_uuid, snapshot_digest, reference_kind "
                + "FROM entity_canonical_roster_retention_references WHERE reference_uuid = ?",
            referenceUuid);
    if (retained == null
        || !operationUuid.equals(retained.get("acquire_operation_uuid", UUID.class))
        || !binding.snapshot().snapshotUuid().equals(retained.get("snapshot_uuid", UUID.class))
        || !binding
            .snapshot()
            .snapshotDigest()
            .equals(retained.get("snapshot_digest", String.class))
        || !kind.name().equals(retained.get("reference_kind", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_REFERENCE_IDEMPOTENCY_CONFLICT");
    }
  }

  /** A release is an immutable exact operation result, never an expiry inferred from age. */
  public void release(
      CanonicalGameplayRosterRetentionBinding binding, UUID operationUuid, UUID referenceUuid) {
    requireUuid(operationUuid, "operationUuid");
    requireUuid(referenceUuid, "referenceUuid");
    lock(binding);
    if (dsl.fetchExists(
        dsl.selectOne()
            .from("entity_canonical_roster_original_selections")
            .where(org.jooq.impl.DSL.field("operation_uuid", UUID.class).eq(referenceUuid)))) {
      throw new IllegalStateException("CANONICAL_ROSTER_ORIGINAL_RELEASE_AUTHORITY_UNAVAILABLE");
    }
    Record acquisition =
        dsl.fetchOne(
            "SELECT 1 FROM entity_canonical_roster_retention_references "
                + "WHERE reference_uuid = ? AND snapshot_uuid = ? AND snapshot_digest = ?",
            referenceUuid,
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest());
    if (acquisition == null) {
      throw new IllegalStateException("CANONICAL_ROSTER_REFERENCE_UNAVAILABLE");
    }
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retention_releases "
            + "(release_operation_uuid, reference_uuid, snapshot_uuid, snapshot_digest) "
            + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        operationUuid,
        referenceUuid,
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest());
    Record retained =
        dsl.fetchOne(
            "SELECT release_operation_uuid, snapshot_uuid, snapshot_digest "
                + "FROM entity_canonical_roster_retention_releases WHERE reference_uuid = ?",
            referenceUuid);
    if (retained == null
        || !operationUuid.equals(retained.get("release_operation_uuid", UUID.class))
        || !binding.snapshot().snapshotUuid().equals(retained.get("snapshot_uuid", UUID.class))
        || !binding
            .snapshot()
            .snapshotDigest()
            .equals(retained.get("snapshot_digest", String.class))) {
      throw new IllegalStateException("CANONICAL_ROSTER_RELEASE_IDEMPOTENCY_CONFLICT");
    }
  }

  /** No caller can turn zero references into terminal retirement on the current evidence wire. */
  public Protection protection(CanonicalGameplayRosterRetentionBinding binding) {
    lock(binding);
    Record counts =
        dsl.fetchSingle(
            "SELECT count(*) FILTER (WHERE r.reference_kind <> 'HOLD') AS live_references, "
                + "count(*) FILTER (WHERE r.reference_kind = 'HOLD') AS holds "
                + "FROM entity_canonical_roster_retention_references r "
                + "WHERE r.snapshot_uuid = ? AND NOT EXISTS "
                + "(SELECT 1 FROM entity_canonical_roster_retention_releases x "
                + "WHERE x.reference_uuid = r.reference_uuid)",
            binding.snapshot().snapshotUuid());
    Record lifecycle =
        dsl.fetchSingle(
            "SELECT lifecycle_state, retirement_observed_at + minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() AS horizon_passed "
                + "FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
            binding.snapshot().snapshotUuid());
    long references = counts.get("live_references", Long.class);
    long holds = counts.get("holds", Long.class);
    boolean retired = "RETIRED".equals(lifecycle.get("lifecycle_state", String.class));
    return new Protection(
        references,
        holds,
        retired
            && references == 0L
            && holds == 0L
            && Boolean.TRUE.equals(lifecycle.get("horizon_passed", Boolean.class)),
        retired ? "AUTHENTICATED_TARGET_RETIRED" : "AUTHENTICATED_SUPERSESSION_UNAVAILABLE");
  }

  public record Protection(
      long liveReferences, long holds, boolean disposalEligible, String terminality) {}

  public record Health(long oldestBlockingSeconds, long cleanupLagSeconds) {}

  public Health health() {
    requireOwnerTransaction();
    Record row =
        dsl.fetchSingle(
            "SELECT COALESCE(max(extract(epoch FROM clock_timestamp() - recorded_at)) FILTER (WHERE lifecycle_state = 'PROTECTED'), 0)::bigint AS oldest_blocking, "
                + "COALESCE(max(GREATEST(0, extract(epoch FROM clock_timestamp() - retirement_observed_at - minimum_retention_millis * interval '1 millisecond'))) "
                + "FILTER (WHERE lifecycle_state = 'RETIRED'), 0)::bigint AS cleanup_lag FROM entity_canonical_roster_retention_lifecycle");
    return new Health(row.get("oldest_blocking", Long.class), row.get("cleanup_lag", Long.class));
  }

  private void lock(CanonicalGameplayRosterRetentionBinding binding) {
    requireOwnerTransaction();
    Objects.requireNonNull(binding, "binding");
    var target = binding.target();
    Record exact =
        dsl.fetchOne(
            "SELECT snapshot_uuid FROM entity_canonical_gameplay_roster_snapshots "
                + "WHERE snapshot_uuid = ? AND snapshot_digest = ? AND canonical_account_uuid = ? "
                + "AND tenant_uuid = ? AND realm_uuid = ? AND world_slug = ? AND realm_slug = ? "
                + "AND game_instance_uuid = ? AND catalog_revision = ? AND canonical_version_uuid = ? "
                + "AND pointer_version = ? AND active_world_epoch = ? "
                + "AND published_policy_digest = ? AND published_release_bundle_ref = ? "
                + "AND admission_pointer_snapshot_digest = ? AND published_owner_proof_digest = ? "
                + "AND playable_state_namespace_uuid = ? AND playable_state_scope = ? "
                + "AND entry_policy = ? AND construction_state = 'SEALED' "
                + "AND publication_evidence_format = 'CANONICAL_UUID_V2' FOR UPDATE",
            binding.snapshot().snapshotUuid(),
            binding.snapshot().snapshotDigest(),
            binding.canonicalAccountUuid(),
            target.tenantUuid(),
            target.realmUuid(),
            target.worldSlug(),
            target.realmSlug(),
            target.gameInstanceUuid(),
            target.catalogRevision(),
            target.canonicalVersionUuid(),
            target.pointerVersion(),
            target.activeWorldEpoch(),
            target.publishedPolicyDigest(),
            target.publishedReleaseBundleRef(),
            target.admissionPointerSnapshotDigest(),
            target.publishedOwnerProofDigest(),
            target.playableStateNamespaceId(),
            target.playableStateScope().name(),
            target.entryPolicy().name());
    if (exact == null) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_TARGET_MISMATCH");
    }
    dsl.execute(
        "INSERT INTO entity_canonical_roster_retention_lifecycle "
            + "(snapshot_uuid, snapshot_digest) VALUES (?, ?) ON CONFLICT DO NOTHING",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest());
    dsl.fetchSingle(
        "SELECT snapshot_uuid FROM entity_canonical_roster_retention_lifecycle "
            + "WHERE snapshot_uuid = ? AND snapshot_digest = ? FOR UPDATE",
        binding.snapshot().snapshotUuid(),
        binding.snapshot().snapshotDigest());
  }

  private static void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("CANONICAL_ROSTER_RETENTION_OWNER_TRANSACTION_REQUIRED");
    }
  }

  private static void requireUuid(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must be non-nil");
    }
  }
}
