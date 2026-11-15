package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Storage owner for canonical initial-admission holds in the existing World hold table. */
@Repository
public final class WorldCanonicalInitialAdmissionHoldRepository {
  private static final String SELECT_COLUMNS =
      "hold_id, hold_fence, canonical_request_bytes, hold_binding_digest, status, "
          + "reconciliation_error, canonical_owner_proof_bytes, canonical_owner_proof_digest, "
          + "owner_proof_id, owner_pointer_audit_id, owner_pointer_version, row_version";

  private final DSLContext dsl;

  public WorldCanonicalInitialAdmissionHoldRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /** Acquires only a typed run-owned fixture row after its caller has verified the live owners. */
  public Optional<HoldRecord> insertFixtureHold(
      HoldIdentity identity,
      WorldCanonicalInstanceAssociation association,
      Instant now) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(association, "association");
    Objects.requireNonNull(now, "now");
    Request request = identity.request();
    if (request.authorityKind()
            != WorldCanonicalInitialAdmissionHold.AuthorityKind.RUN_OWNED_FIXTURE
        || !association.isRunOwnedFixture()) {
      throw new IllegalArgumentException("Run-owned fixture authority is required");
    }
    var keys = association.worldPrivateKeys();
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO initial_admission_bind_hold (hold_id, hold_fence, tenant_id, realm_uuid, "
                + "playable_state_namespace_uuid, playable_state_scope, game_instance_id, version_id, "
                + "active_lifecycle_epoch, initial_admission_request_id, request_digest, "
                + "expected_no_prior_pointer, expected_catalog_revision, status, diagnostic_expires_at, "
                + "created_at, updated_at, row_version, canonical_target_namespace, canonical_tenant_id, "
                + "canonical_world_slug, canonical_game_instance_id, canonical_version_id, "
                + "initial_admission_origin, expected_prior_pointer_version, canonical_request_bytes, "
                + "hold_binding_digest) VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'PENDING', ?, ?, ?, 0, ?, ?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING RETURNING "
                + SELECT_COLUMNS,
            identity.holdId(),
            identity.holdFence(),
            keys.tenantId(),
            request.realmId(),
            request.playableStateNamespaceId(),
            request.playableStateScope(),
            keys.gameInstanceId(),
            keys.versionId(),
            request.activeLifecycleEpoch(),
            request.initialAdmissionRequestId(),
            request.initialAdmissionRequestDigest(),
            request.initialAdmissionOrigin()
                == WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            request.expectedCatalogRevision(),
            toLocalDateTime(now.plusSeconds(300)),
            toLocalDateTime(now),
            toLocalDateTime(now),
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.worldSlug(),
            request.canonicalGameInstanceId(),
            request.canonicalVersionId(),
            request.initialAdmissionOrigin().name(),
            request.expectedPriorPointerVersion(),
            request.canonicalRequestBytes(),
            identity.holdBindingDigest());
    return Optional.ofNullable(inserted).map(this::toHoldRecord);
  }

  /** Historical identity read is deliberately independent of an ambient transaction. */
  public Optional<HoldIdentity> findIdentity(Request request) {
    requireNoActiveTransaction("Canonical initial-admission identity read");
    return findIdentityInCurrentTransaction(request);
  }

  /** Same lookup for the acquisition transaction's unique-conflict retry path. */
  public Optional<HoldIdentity> findIdentityInCurrentTransaction(Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT hold_id, hold_fence, canonical_request_bytes, hold_binding_digest "
                + "FROM initial_admission_bind_hold WHERE canonical_target_namespace=? "
                + "AND canonical_tenant_id=?::uuid AND canonical_game_instance_id=?::uuid "
                + "AND initial_admission_request_id=?",
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.canonicalGameInstanceId(),
            request.initialAdmissionRequestId());
    if (row == null) return Optional.empty();
    HoldIdentity identity = identity(row);
    requireSameRequest(identity, request);
    return Optional.of(identity);
  }

  public Optional<HoldRecord> findByIdentity(HoldIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM initial_admission_bind_hold "
                + "WHERE hold_id=?::uuid AND hold_fence=?::uuid AND canonical_request_bytes IS NOT NULL",
            identity.holdId(),
            identity.holdFence());
    if (row == null) return Optional.empty();
    HoldRecord found = toHoldRecord(row);
    requireSameIdentity(found.identity(), identity);
    return Optional.of(found);
  }

  public Optional<HoldRecord> findByHoldId(String holdId) {
    Record row =
        dsl.fetchOne(
            "SELECT " + SELECT_COLUMNS + " FROM initial_admission_bind_hold "
                + "WHERE hold_id=?::uuid AND canonical_request_bytes IS NOT NULL",
            UUID.fromString(holdId));
    return Optional.ofNullable(row).map(this::toHoldRecord);
  }

  /** Candidate listing is typed from the canonical retained bytes, never inferred from numeric IDs. */
  public List<HoldRecord> findNonterminalFixtureHolds(int limit) {
    return dsl.fetch(
            "SELECT " + SELECT_COLUMNS + " FROM initial_admission_bind_hold "
                + "WHERE status IN ('PENDING','RECONCILIATION_REQUIRED') "
                + "AND canonical_request_bytes IS NOT NULL "
                + "AND convert_from(canonical_request_bytes,'UTF8')::jsonb->'request'->>'authorityKind'="
                + "'RUN_OWNED_FIXTURE' ORDER BY updated_at, hold_id LIMIT ?",
            Math.max(1, Math.min(limit, 256)))
        .map(this::toHoldRecord);
  }

  public boolean isRunOwnedFixtureHold(String holdId) {
    Record row =
        dsl.fetchOne(
            "SELECT convert_from(canonical_request_bytes,'UTF8')::jsonb->'request'->>'authorityKind' "
                + "AS authority_kind FROM initial_admission_bind_hold "
                + "WHERE hold_id=?::uuid AND canonical_request_bytes IS NOT NULL",
            UUID.fromString(holdId));
    return row != null && "RUN_OWNED_FIXTURE".equals(row.get("authority_kind", String.class));
  }

  /** One coherent read of immutable identity, current lifecycle, and hold state. */
  public Optional<LifecycleHoldRecord> findLifecycleStateInReadOnlySnapshot(
      HoldIdentity identity,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {
    requireReadOnlyRepeatableReadTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT h.hold_id,h.hold_fence,h.canonical_request_bytes,h.hold_binding_digest,h.status, "
                + "h.reconciliation_error,h.canonical_owner_proof_bytes,h.canonical_owner_proof_digest, "
                + "h.owner_proof_id,h.owner_pointer_audit_id,h.owner_pointer_version,h.row_version, "
                + "a.world_instance_id,a.canonical_authority_kind,a.fixture_provenance_digest, "
                + "a.preparation_input_digest,a.canonical_target_namespace,a.canonical_tenant_id, "
                + "a.canonical_world_slug,a.canonical_game_instance_id,a.playable_state_namespace_id, "
                + "a.playable_state_scope,a.public_production,a.canonical_realm_id,a.canonical_version_id, "
                + "a.catalog_revision,a.control_plane_request_id,a.descriptor_request_digest, "
                + "a.descriptor_result_digest,w.status AS lifecycle_status,w.lifecycle_epoch,w.row_version AS lifecycle_row_version "
                + "FROM initial_admission_bind_hold h "
                + "JOIN world_canonical_instance_association a ON a.world_instance_id = "
                + "(SELECT wi.id FROM world_instance wi WHERE wi.tenant_id=h.tenant_id "
                + "AND wi.game_instance_id=h.game_instance_id AND wi.version_id=h.version_id) "
                + "JOIN world_instance w ON w.id=a.world_instance_id "
                + "WHERE h.hold_id=?::uuid AND h.hold_fence=?::uuid",
            identity.holdId(),
            identity.holdFence());
    if (row == null) return Optional.empty();
    HoldRecord hold = toHoldRecord(row);
    requireSameIdentity(hold.identity(), identity);
    var request = identity.request();
    if (hold.identity().request().authorityKind()
            != WorldCanonicalInitialAdmissionHold.AuthorityKind.RUN_OWNED_FIXTURE
        || !"RUN_OWNED_FIXTURE".equals(row.get("canonical_authority_kind", String.class))
        || !request.fixturePolicySealEvidenceDigest()
            .equals(row.get("fixture_provenance_digest", String.class))
        || !lifecycleRequest.targetNamespace().equals(request.targetNamespace())
        || !lifecycleRequest.canonicalTenantId().equals(request.canonicalTenantId())
        || !lifecycleRequest.worldSlug().equals(request.worldSlug())
        || !lifecycleRequest.canonicalGameInstanceId().equals(request.canonicalGameInstanceId())
        || !lifecycleRequest.playableStateNamespaceId().equals(request.playableStateNamespaceId())
        || !lifecycleRequest.playableStateScope().equals(request.playableStateScope())
        || !lifecycleRequest.canonicalVersionId().equals(request.canonicalVersionId())
        || lifecycleRequest.authorityKind()
            != WorldCanonicalInitialAdmissionHold.AuthorityKind.RUN_OWNED_FIXTURE
        || !request.fixturePolicySealEvidenceDigest()
            .equals(lifecycleRequest.fixturePolicySealEvidenceDigest())
        || !lifecycleRequest.controlPlaneRequestId().equals(row.get("control_plane_request_id", String.class))
        || !lifecycleRequest.expectedDescriptorRequestDigest().equals(row.get("descriptor_request_digest", String.class))
        || !lifecycleRequest.expectedDescriptorResultDigest().equals(row.get("descriptor_result_digest", String.class))
        || !request.canonicalTenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || !request.targetNamespace().equals(row.get("canonical_target_namespace", String.class))
        || !request.worldSlug().equals(row.get("canonical_world_slug", String.class))
        || !request.realmId().equals(row.get("canonical_realm_id", UUID.class))
        || !request.canonicalVersionId().equals(row.get("canonical_version_id", UUID.class))
        || !request.playableStateNamespaceId().equals(row.get("playable_state_namespace_id", UUID.class))
        || !request.canonicalGameInstanceId().equals(row.get("canonical_game_instance_id", UUID.class))
        || !request.playableStateScope().equals(row.get("playable_state_scope", String.class))
        || request.expectedCatalogRevision() != row.get("catalog_revision", Long.class)
        || !Boolean.TRUE.equals(row.get("public_production", Boolean.class))
        || !"ACTIVE".equals(row.get("lifecycle_status", String.class))
        || row.get("lifecycle_epoch", Long.class) != 2L
        || row.get("lifecycle_row_version", Long.class) != 1L) {
      throw new IllegalArgumentException("RUN_OWNED_WORLD_INITIAL_HOLD_EVIDENCE_MISMATCH");
    }
    return Optional.of(new LifecycleHoldRecord(hold, lifecycleRequest));
  }

  /** The SQL function owns the row lock, exact CAS, and one-use reconciliation manifest. */
  public void markReconciliationRequired(HoldIdentity identity, String errorCode) {
    requireWritableReadCommittedTransaction();
    dsl.fetchOne(
        "SELECT world_require_canonical_initial_admission_hold_reconciliation(?::uuid, ?::uuid, ?)",
        identity.holdId(),
        identity.holdFence(),
        errorCode);
  }

  /** The SQL function locks the World association before the hold and consumes the exact proof once. */
  public void finalizeWithOwnerProof(
      HoldIdentity identity,
      HoldStatus terminalStatus,
      String proofId,
      String proofDigest,
      String pointerAuditId,
      Long pointerVersion,
      Instant terminalAt,
      boolean positiveDurableAbort,
      byte[] ownerProofBytes,
      String canonicalProofDigest) {
    requireWritableReadCommittedTransaction();
    dsl.fetchOne(
        "SELECT world_finalize_canonical_initial_admission_hold(?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        identity.holdId(),
        identity.holdFence(),
        terminalStatus.name(),
        proofId,
        proofDigest,
        pointerAuditId,
        pointerVersion,
        toLocalDateTime(terminalAt),
        positiveDurableAbort,
        ownerProofBytes,
        canonicalProofDigest);
  }

  private HoldIdentity identity(Record row) {
    byte[] requestBytes = row.get("canonical_request_bytes", byte[].class);
    Request request = Request.fromStored(requestBytes);
    String holdBindingDigest = row.get("hold_binding_digest", String.class);
    if (!request.holdBindingDigest().equals(holdBindingDigest)) {
      throw new IllegalStateException("Canonical initial-admission hold digest is corrupt");
    }
    return new HoldIdentity(
        request,
        row.get("hold_id", UUID.class),
        row.get("hold_fence", UUID.class));
  }

  private HoldRecord toHoldRecord(Record row) {
    HoldIdentity identity = identity(row);
    return new HoldRecord(
        identity,
        HoldStatus.valueOf(row.get("status", String.class)),
        row.get("reconciliation_error", String.class),
        row.get("canonical_owner_proof_bytes", byte[].class),
        row.get("canonical_owner_proof_digest", String.class),
        row.get("owner_proof_id", String.class),
        row.get("owner_pointer_audit_id", String.class),
        row.get("owner_pointer_version", Long.class),
        row.get("row_version", Long.class));
  }

  private static void requireSameRequest(HoldIdentity identity, Request expected) {
    if (!identity.request().equals(expected)) {
      throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT: canonical hold request changed");
    }
  }

  private static void requireSameIdentity(HoldIdentity actual, HoldIdentity expected) {
    if (!actual.equals(expected)
        || !actual.holdBindingDigest().equals(expected.holdBindingDigest())) {
      throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT: canonical hold identity changed");
    }
  }

  private static LocalDateTime toLocalDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " requires an independent committed read");
    }
  }

  private static void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException("Canonical World hold write requires its owned READ COMMITTED transaction");
    }
  }

  private static void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException("Canonical World hold state requires a read-only REPEATABLE READ snapshot");
    }
  }

  public record HoldRecord(
      HoldIdentity identity,
      HoldStatus status,
      String reconciliationError,
      byte[] ownerProofBytes,
      String ownerProofDigest,
      String ownerProofId,
      String ownerPointerAuditId,
      Long ownerPointerVersion,
      long rowVersion) {
    public HoldRecord {
      ownerProofBytes = ownerProofBytes == null ? null : ownerProofBytes.clone();
    }

    @Override
    public byte[] ownerProofBytes() {
      return ownerProofBytes == null ? null : ownerProofBytes.clone();
    }
  }

  public record LifecycleHoldRecord(HoldRecord hold, WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest) {}
}
