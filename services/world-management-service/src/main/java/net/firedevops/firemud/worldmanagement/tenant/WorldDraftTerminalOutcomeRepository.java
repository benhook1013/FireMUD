package net.firedevops.firemud.worldmanagement.tenant;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.OpenOwner;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal immutable no-commit storage. It does not authenticate or authorize a Draft operation.
 */
public final class WorldDraftTerminalOutcomeRepository {
  private static final String TABLE = "world_draft_terminal_outcome";

  private final DSLContext dsl;
  private final WorldDesignPublicationFenceRepository fence;
  private final ObjectMapper mapper;

  public WorldDraftTerminalOutcomeRepository(
      DSLContext dsl, WorldDesignPublicationFenceRepository fence, ObjectMapper mapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  /** Absence stays UNKNOWN; committed reads never create rows or infer an abort. */
  public Optional<WorldDraftTerminalOutcome> readDefinitiveAbort(
      WorldDraftTerminalOperation operation) {
    Objects.requireNonNull(operation, "operation");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("World terminal readback requires a committed read");
    }
    List<Record> rows = find(operation);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() != 1) {
      throw new ConflictException("World terminal identities resolve to conflicting outcomes");
    }
    return Optional.of(readExact(operation, rows.getFirst()));
  }

  /**
   * Reconstructs an exact read only from retained World terminal/source identity rows. Absence is
   * UNKNOWN; conflicts or incomplete retained associations are never translated to an abort.
   */
  public Optional<WorldDraftTerminalOutcome> readDefinitiveAbort(
      String targetNamespace, byte[] originalAccountBinding) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("World terminal readback requires a committed read");
    }
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace is required");
    }
    byte[] original = DraftAuthorizationFenceBinding.bytes(originalAccountBinding);
    DraftAuthorizationFenceBinding account = DraftAuthorizationFenceBinding.fromStored(original);
    List<Record> rows = find(account);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() != 1) {
      throw new ConflictException("World terminal identities resolve to conflicting outcomes");
    }

    Record row = rows.getFirst();
    DraftCommitBinding binding;
    WorldDesignPublicationFenceEvidence.OwnerBinding owner;
    try {
      binding =
          DraftCommitBinding.fromStored(
              row.get("binding_json", String.class), row.get("binding_digest", String.class));
      owner =
          mapper.readValue(
              row.get("owner_binding_json", String.class),
              WorldDesignPublicationFenceEvidence.OwnerBinding.class);
    } catch (RuntimeException invalidStoredBinding) {
      throw new ConflictException("World terminal row has an invalid retained operation binding");
    }

    WorldDraftTerminalOperation operation;
    try {
      operation =
          new WorldDraftTerminalOperation(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId(),
              binding,
              owner,
              original);
    } catch (RuntimeException invalidRetainedEvidence) {
      throw new ConflictException(
          "World terminal row differs from the complete original Account binding");
    }
    requireRetainedSourceAssociation(targetNamespace, operation, row);
    try {
      return Optional.of(readExact(operation, row));
    } catch (ConflictException conflict) {
      throw conflict;
    } catch (RuntimeException invalidRetainedOutcome) {
      throw new ConflictException("World terminal outcome failed immutable integrity readback");
    }
  }

  /** Caller owns a short writable READ_COMMITTED transaction. */
  public WorldDraftTerminalOutcome recordDefinitiveAbort(WorldDraftTerminalOperation operation) {
    requireTransaction();
    List<Record> prior = find(operation);
    if (!prior.isEmpty()) {
      if (prior.size() != 1) {
        throw new ConflictException("World terminal identities resolve to conflicting outcomes");
      }
      return readExact(operation, prior.getFirst());
    }

    OpenOwner owner = fence.lockOpenAndResolve(operation.ownerBinding());
    if (!owner.binding().equals(operation.ownerBinding()) || owner.localVersionKey() <= 0L) {
      throw new ConflictException("World terminal request differs from the locked V25 owner scope");
    }

    // Recheck after V25 serialization. A commit that won the row lock is immutable history,
    // including the older STORED_PERMISSION_UNVERIFIED component rows.
    prior = find(operation);
    if (!prior.isEmpty()) {
      if (prior.size() != 1) {
        throw new ConflictException("World terminal identities resolve to conflicting outcomes");
      }
      return readExact(operation, prior.getFirst());
    }
    requireNoWorldApplication(operation, owner);
    List<WorldDraftTerminalOutcome.ObservedEpoch> observed = observeExactEpochs(operation, owner);
    OffsetDateTime recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
    WorldDraftTerminalOutcome outcome =
        WorldDraftTerminalOutcome.create(operation, observed, recordedAt);
    insert(operation, owner, outcome);
    List<Record> inserted = find(operation);
    if (inserted.size() != 1) {
      throw new ConflictException("World definitive abort was not stored exactly once");
    }
    return readExact(operation, inserted.getFirst());
  }

  private void insert(
      WorldDraftTerminalOperation operation, OpenOwner owner, WorldDraftTerminalOutcome outcome) {
    try {
      dsl.execute(
          "INSERT INTO "
              + TABLE
              + " (operation_id,request_id,commit_id,authorization_fence_id,target_namespace,"
              + "canonical_tenant_id,canonical_version_id,version_identity_operation_id,"
              + "local_tenant_key,local_version_key,owner_binding_json,binding_json,binding_digest,"
              + "account_binding_bytes,account_binding_digest,affected_units_json,outcome,"
              + "outcome_bytes,outcome_digest,recorded_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)",
          operation.operationId(),
          operation.requestId(),
          operation.commitId(),
          operation.authorizationFenceId(),
          operation.ownerBinding().targetNamespace(),
          operation.canonicalTenantId(),
          operation.canonicalVersionId(),
          operation.ownerBinding().versionIdentityOperationId(),
          owner.localTenantKey(),
          owner.localVersionKey(),
          mapper.writeValueAsString(operation.ownerBinding()),
          operation.binding().canonicalJson(),
          operation.binding().digest(),
          operation.accountBindingBytes(),
          operation.accountBindingDigest(),
          encodeAffectedUnits(
              operation.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT)),
          outcome.outcome(),
          outcome.canonicalBytes(),
          outcome.digest(),
          outcome.recordedAt());
    } catch (RuntimeException failure) {
      throw failure;
    }
  }

  private String encodeAffectedUnits(List<AffectedUnit> affectedUnits) {
    List<java.util.Map<String, String>> values = new ArrayList<>();
    for (AffectedUnit unit : affectedUnits) {
      java.util.Map<String, String> value = new java.util.LinkedHashMap<>();
      value.put("owner", unit.owner().name());
      value.put("aggregateType", unit.aggregateType());
      value.put("aggregateId", unit.aggregateId());
      value.put("scopeType", unit.scopeType());
      value.put("scopeId", unit.scopeId());
      value.put("expectedEpoch", unit.expectedEpoch());
      values.add(value);
    }
    return mapper.writeValueAsString(values);
  }

  private List<Record> find(WorldDraftTerminalOperation operation) {
    return dsl.fetch(
        "SELECT * FROM "
            + TABLE
            + " WHERE operation_id = ? OR request_id = ? OR commit_id = ? OR authorization_fence_id = ?",
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId());
  }

  private List<Record> find(DraftAuthorizationFenceBinding account) {
    return dsl.fetch(
        "SELECT * FROM "
            + TABLE
            + " WHERE operation_id = ? OR request_id = ? OR commit_id = ? OR authorization_fence_id = ?",
        account.operationId(),
        account.requestId(),
        account.commitId(),
        account.fenceId());
  }

  private void requireRetainedSourceAssociation(
      String targetNamespace, WorldDraftTerminalOperation operation, Record row) {
    var owner = operation.ownerBinding();
    if (!targetNamespace.equals(owner.targetNamespace())
        || !targetNamespace.equals(row.get("target_namespace", String.class))) {
      throw new ConflictException("World terminal read uses another workload namespace");
    }
    Long localTenantKey = row.get("local_tenant_key", Long.class);
    Long localVersionKey = row.get("local_version_key", Long.class);
    if (localTenantKey == null || localVersionKey == null) {
      throw new ConflictException("World terminal source association is incomplete");
    }

    Record identity =
        dsl.fetchOne(
            "SELECT * FROM world_authored_version_identity WHERE operation_id = ?",
            owner.versionIdentityOperationId());
    if (identity == null
        || !owner
            .versionIdentityOperationId()
            .equals(identity.get("operation_id", java.util.UUID.class))
        || !targetNamespace.equals(identity.get("target_namespace", String.class))
        || !owner
            .canonicalTenantId()
            .equals(identity.get("canonical_tenant_id", java.util.UUID.class))
        || !owner
            .canonicalVersionId()
            .equals(identity.get("canonical_version_id", java.util.UUID.class))
        || !Objects.equals(
            owner.gameDesignVersionId(), identity.get("game_design_version_id", Long.class))
        || !Objects.equals(localTenantKey, identity.get("local_tenant_key", Long.class))
        || !Objects.equals(localVersionKey, identity.get("local_version_key", Long.class))
        || !owner.intakeRequestId().equals(identity.get("intake_request_id", java.util.UUID.class))
        || !owner
            .intakeOperationId()
            .equals(identity.get("intake_operation_id", java.util.UUID.class))
        || !owner.intakeRequestDigest().equals(identity.get("intake_request_digest", String.class))
        || !owner
            .sourceOperationId()
            .equals(identity.get("source_operation_id", java.util.UUID.class))
        || !owner
            .sourceEvidenceDigest()
            .equals(identity.get("source_evidence_digest", String.class))
        || !owner
            .intakeReceiptDigest()
            .equals(identity.get("intake_receipt_digest", String.class))) {
      throw new ConflictException(
          "World terminal source binding differs from the retained V27 identity");
    }

    Record versionOwner =
        dsl.fetchOne(
            "SELECT target_namespace, canonical_tenant_id, local_tenant_key, version_id "
                + "FROM world_design_publication_fence_owner "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                + "AND local_tenant_key = ? AND version_id = ?",
            targetNamespace,
            owner.canonicalTenantId(),
            localTenantKey,
            localVersionKey);
    if (versionOwner == null
        || !targetNamespace.equals(versionOwner.get("target_namespace", String.class))
        || !owner
            .canonicalTenantId()
            .equals(versionOwner.get("canonical_tenant_id", java.util.UUID.class))
        || !Objects.equals(localTenantKey, versionOwner.get("local_tenant_key", Long.class))
        || !Objects.equals(localVersionKey, versionOwner.get("version_id", Long.class))) {
      throw new ConflictException(
          "World terminal source binding differs from the retained V25 owner association");
    }
  }

  private WorldDraftTerminalOutcome readExact(WorldDraftTerminalOperation operation, Record row) {
    if (!operation.operationId().equals(row.get("operation_id", java.util.UUID.class))
        || !operation.requestId().equals(row.get("request_id", java.util.UUID.class))
        || !operation.commitId().equals(row.get("commit_id", java.util.UUID.class))
        || !operation
            .authorizationFenceId()
            .equals(row.get("authorization_fence_id", java.util.UUID.class))
        || !operation
            .canonicalTenantId()
            .equals(row.get("canonical_tenant_id", java.util.UUID.class))
        || !operation
            .canonicalVersionId()
            .equals(row.get("canonical_version_id", java.util.UUID.class))
        || !operation
            .ownerBinding()
            .targetNamespace()
            .equals(row.get("target_namespace", String.class))
        || !mapper
            .writeValueAsString(operation.ownerBinding())
            .equals(row.get("owner_binding_json", String.class))
        || !operation
            .ownerBinding()
            .versionIdentityOperationId()
            .equals(row.get("version_identity_operation_id", java.util.UUID.class))
        || !operation.binding().canonicalJson().equals(row.get("binding_json", String.class))
        || !operation.binding().digest().equals(row.get("binding_digest", String.class))
        || !Arrays.equals(
            operation.accountBindingBytes(), row.get("account_binding_bytes", byte[].class))
        || !operation.accountBindingDigest().equals(row.get("account_binding_digest", String.class))
        || !"DEFINITIVELY_ABORTED".equals(row.get("outcome", String.class))) {
      throw new ConflictException(
          "World terminal identity was reused with changed binding or evidence");
    }
    return WorldDraftTerminalOutcome.fromStored(
        operation,
        row.get("outcome_bytes", byte[].class),
        row.get("outcome_digest", String.class),
        row.get("recorded_at", OffsetDateTime.class));
  }

  private void requireNoWorldApplication(WorldDraftTerminalOperation operation, OpenOwner owner) {
    if (exists(
            "SELECT 1 FROM world_region_draft_commit WHERE request_id = ? OR commit_id = ?",
            operation.requestId(),
            operation.commitId())
        || exists(
            "SELECT 1 FROM world_topology_draft_commit WHERE request_id = ? OR commit_id = ?",
            operation.requestId(),
            operation.commitId())
        || exists(
            "SELECT 1 FROM world_authored_topology_identity "
                + "WHERE request_id = ? OR commit_id = ?",
            operation.requestId(),
            operation.commitId())
        || exists(
            "SELECT 1 FROM world_design_revision_ledger "
                + "WHERE tenant_id = ? AND version_id = ? AND commit_id = ?",
            owner.localTenantKey(),
            owner.localVersionKey(),
            operation.commitId().toString())) {
      throw new ConflictException(
          "Existing World application or identity history cannot be relabeled as no-commit");
    }
  }

  private List<WorldDraftTerminalOutcome.ObservedEpoch> observeExactEpochs(
      WorldDraftTerminalOperation operation, OpenOwner owner) {
    List<AffectedUnit> expected =
        operation.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    List<WorldDraftTerminalOutcome.ObservedEpoch> observed = new ArrayList<>(expected.size());
    for (AffectedUnit unit : expected) {
      long current =
          "AGGREGATE".equals(unit.scopeType())
              ? aggregateEpoch(unit, operation, owner)
              : scopeEpoch(unit, owner);
      if (!Long.toString(current).equals(unit.expectedEpoch())) {
        throw new ConflictException(
            "Affected World epoch changed; definitive no-commit cannot be proved");
      }
      observed.add(new WorldDraftTerminalOutcome.ObservedEpoch(unit, Long.toString(current)));
    }
    return List.copyOf(observed);
  }

  private long aggregateEpoch(
      AffectedUnit unit, WorldDraftTerminalOperation operation, OpenOwner owner) {
    long aggregateKey;
    if (unit.aggregateId().matches("[1-9][0-9]*")) {
      try {
        aggregateKey = Long.parseLong(unit.aggregateId());
      } catch (NumberFormatException overflow) {
        throw new ConflictException("World numeric aggregate identity is out of range");
      }
    } else {
      java.util.UUID templateId;
      try {
        templateId = java.util.UUID.fromString(unit.aggregateId());
      } catch (IllegalArgumentException malformed) {
        throw new ConflictException(
            "World aggregate identity is neither canonical UUID nor private key");
      }
      if (!templateId.toString().equals(unit.aggregateId())) {
        throw new ConflictException("World aggregate UUID is not canonical");
      }
      Record mapping =
          dsl.fetchOne(
              "SELECT private_row_key FROM world_authored_topology_identity "
                  + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                  + "AND canonical_version_id = ? AND family = ? AND template_id = ?",
              operation.ownerBinding().targetNamespace(),
              operation.canonicalTenantId(),
              operation.canonicalVersionId(),
              unit.aggregateType(),
              templateId);
      if (mapping == null) {
        return 0L;
      }
      aggregateKey = mapping.get("private_row_key", Long.class);
    }
    Record row =
        dsl.fetchOne(
            "SELECT draft_revision_epoch FROM world_design_aggregate_epoch "
                + "WHERE tenant_id = ? AND version_id = ? AND aggregate_type = ? AND aggregate_id = ?",
            owner.localTenantKey(),
            owner.localVersionKey(),
            unit.aggregateType(),
            aggregateKey);
    return row == null ? 0L : row.get("draft_revision_epoch", Long.class);
  }

  private long scopeEpoch(AffectedUnit unit, OpenOwner owner) {
    Record row =
        dsl.fetchOne(
            "SELECT draft_scope_revision_epoch FROM world_design_scope_epoch "
                + "WHERE tenant_id = ? AND version_id = ? AND scope_type = ? AND scope_id = ?",
            owner.localTenantKey(),
            owner.localVersionKey(),
            unit.scopeType(),
            unit.scopeId());
    return row == null ? 0L : row.get("draft_scope_revision_epoch", Long.class);
  }

  private boolean exists(String sql, Object... bindings) {
    return dsl.fetchOne(sql, bindings) != null;
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new ConflictException("Writable World owner transaction required");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new ConflictException("World terminal outcome requires READ_COMMITTED");
          }
        });
  }
}
