package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService.CommitOrderProof;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Fresh graph mutations and exact original Account receipt share one owner transaction. */
public class WorldDraftGraphApplicationRepository {
  private final DSLContext dsl;
  private final WorldDesignPublicationFenceRepository fence;
  private final WorldDraftTopologyCommitRepository topology;
  private final WorldDraftStartLocationReceiptRepository startLocations;
  private final ObjectMapper mapper;

  @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "No resources or finalizer.")
  public WorldDraftGraphApplicationRepository(
      DSLContext dsl, WorldDesignPublicationFenceRepository fence, ObjectMapper mapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    topology = new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
    startLocations = new WorldDraftStartLocationReceiptRepository(dsl);
  }

  public Optional<WorldDraftGraphAppliedResult> readCommitted(
      WorldDraftGraphApplication application) {
    requireCommittedRead();
    var rows = find(application.operation());
    if (rows.isEmpty()) return Optional.empty();
    if (rows.size() != 1)
      throw new ConflictException("World application identities select conflicting results");
    return Optional.of(readExact(application, rows.getFirst()));
  }

  /**
   * Resolves one exact immutable selected commit to its retained World APPLIED application.
   *
   * <p>The complete Game Design selection is only a lookup and equality constraint. This read
   * derives the private World owner binding from persisted APPLIED evidence and never constructs a
   * World operation, guesses a local owner key, or grants permission to freeze.
   */
  Optional<WorldDraftGraphApplication> readSelectedPublicationApplication(
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      AuthoredDraftPublishSelectionBinding suppliedSelection) {
    requireCommittedRead();
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace is required");
    }
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(suppliedSelection, "suppliedSelection");
    final AuthoredDraftPublishSelectionBinding selection;
    try {
      selection =
          AuthoredDraftPublishSelectionBinding.fromStored(
              suppliedSelection.canonicalJson(), suppliedSelection.digest());
    } catch (RuntimeException invalid) {
      throw new ConflictException("World selected-publication lookup has invalid selection bytes");
    }
    if (!selection.intent().canonicalTenantId().equals(canonicalTenantId)
        || !selection.intent().canonicalVersionId().equals(canonicalVersionId)) {
      throw new ConflictException(
          "World selected-publication lookup differs from its canonical tenant or version");
    }

    DraftCommitBinding selectedCommit = selection.selectedCommit();
    var rows =
        dsl.fetch(
            selectSql() + " WHERE a.request_id=? OR a.commit_id=?",
            selectedCommit.requestId(),
            selectedCommit.commitId());
    if (rows.isEmpty()) return Optional.empty();
    if (rows.size() != 1) {
      throw new ConflictException(
          "World selected-publication request and commit identify conflicting applications");
    }

    Record row = rows.getFirst();
    WorldDraftGraphApplication application = reconstruct(row);
    WorldDraftGraphAppliedResult result = readExact(application, row);
    if (!"APPLIED".equals(result.status())
        || !selectedCommit.equals(application.operation().binding())
        || !Arrays.equals(
            selectedCommit.canonicalBytes(), application.operation().binding().canonicalBytes())
        || !selectedCommit.digest().equals(application.operation().binding().digest())
        || !selectedCommit.requestId().equals(application.operation().requestId())
        || !selectedCommit.commitId().equals(application.operation().commitId())
        || !selection.target().equals(application.operation().binding().target())
        || application
            .plan()
            .graph()
            .freshGraphDeclaration()
            .flatMap(WorldDraftTopologyInputGraph.FreshGraphDeclaration::inboundSourceClosure)
            .isEmpty()
        || !targetNamespace.equals(application.operation().ownerBinding().targetNamespace())
        || !canonicalTenantId.equals(application.operation().canonicalTenantId())
        || !canonicalVersionId.equals(application.operation().canonicalVersionId())
        || !canonicalTenantId.equals(application.operation().ownerBinding().canonicalTenantId())
        || !canonicalVersionId.equals(application.operation().ownerBinding().canonicalVersionId())
        || selection.target().gameDesignVersionRowId()
            != application.operation().ownerBinding().gameDesignVersionId()) {
      throw new ConflictException(
          "World retained APPLIED application differs from the complete selected Draft binding");
    }
    return Optional.of(application);
  }

  /** Exact original Account recovery selector; decoding is not workload authentication. */
  public Optional<WorldDraftGraphAppliedResult> readCommitted(
      String targetNamespace, byte[] originalAccountBinding) {
    requireCommittedRead();
    return readByOriginalAccountBinding(targetNamespace, originalAccountBinding);
  }

  /** Reads the original namespace and Account selection inside the caller-owned RR snapshot. */
  Optional<WorldDraftGraphAppliedResult> readInOwnedSnapshot(
      String targetNamespace, byte[] originalAccountBinding) {
    requireOwnedReadOnlyRepeatableReadSnapshot();
    return readByOriginalAccountBinding(targetNamespace, originalAccountBinding);
  }

  private Optional<WorldDraftGraphAppliedResult> readByOriginalAccountBinding(
      String targetNamespace, byte[] originalAccountBinding) {
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace is required");
    }
    var account = DraftAuthorizationFenceBinding.fromStored(originalAccountBinding);
    var rows =
        dsl.fetch(
            selectSql()
                + " WHERE a.operation_id=? OR a.request_id=? OR a.commit_id=? OR a.authorization_fence_id=?",
            account.operationId(),
            account.requestId(),
            account.commitId(),
            account.fenceId());
    if (rows.isEmpty()) return Optional.empty();
    if (rows.size() != 1)
      throw new ConflictException("World recovery identities select conflicting results");
    var application = reconstruct(rows.getFirst());
    if (!targetNamespace.equals(application.operation().ownerBinding().targetNamespace())
        || !Arrays.equals(
            account.canonicalBytes(), application.operation().accountBindingBytes())) {
      throw new ConflictException(
          "World committed recovery differs from original namespace or full Account binding");
    }
    return Optional.of(readExact(application, rows.getFirst()));
  }

  private void requireOwnedReadOnlyRepeatableReadSnapshot() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new ConflictException(
          "World application snapshot read requires an active read-only REPEATABLE READ owner transaction");
    }
    Integer jdbcIsolation = dsl.connectionResult(java.sql.Connection::getTransactionIsolation);
    if (!Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ).equals(jdbcIsolation)) {
      throw new ConflictException(
          "World application snapshot read requires actual JDBC REPEATABLE READ isolation");
    }
  }

  WorldDraftGraphAppliedResult apply(
      WorldDraftGraphApplication application, CommitOrderProof proof) {
    requireTransaction();
    proof.requireExact(application.operation());
    var prior = find(application.operation());
    if (!prior.isEmpty()) return readUnique(application, prior);
    fence.lockOpenAndResolve(application.operation().ownerBinding());
    prior = find(application.operation());
    if (!prior.isEmpty()) return readUnique(application, prior);
    if (application.plan().graph().freshGraphDeclaration().isEmpty()
        || application
            .plan()
            .graph()
            .freshGraphDeclaration()
            .orElseThrow()
            .inboundSourceClosure()
            .isEmpty()) {
      throw new ConflictException(
          "New World graph applications require the original versioned selected-inbound closure declaration");
    }
    var operation = application.operation();
    if (dsl.fetchOne(
            "SELECT 1 FROM world_draft_terminal_outcome WHERE operation_id=? OR request_id=? OR commit_id=? OR authorization_fence_id=?",
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId())
        != null) {
      throw new ConflictException("World operation has definitive no-commit evidence");
    }
    if (dsl.fetchOne(
                "SELECT 1 FROM world_topology_draft_commit WHERE request_id=? OR commit_id=?",
                operation.requestId(),
                operation.commitId())
            != null
        || dsl.fetchOne(
                "SELECT 1 FROM world_region_draft_commit WHERE request_id=? OR commit_id=?",
                operation.requestId(),
                operation.commitId())
            != null) {
      throw new ConflictException(
          "Existing permission-unverified history cannot be promoted to APPLIED");
    }
    // This call genuinely inserts all six families and CAS epochs before the receipt is possible.
    // V39 independently requires that history to have been inserted in THIS transaction.
    var stored = topology.store(application.plan());
    var result = WorldDraftGraphAppliedResult.create(application, stored.graphBytes());
    result
        .startLocationReceipt()
        .ifPresent(
            receipt ->
                startLocations.store(receipt, application.operation().accountBindingBytes()));
    dsl.execute(
        "INSERT INTO world_draft_graph_application (operation_id,request_id,commit_id,authorization_fence_id,"
            + "operation_bytes,account_binding_bytes,account_binding_digest,result_bytes,result_digest) VALUES (?,?,?,?,?,?,?,?,?)",
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId(),
        operation.canonicalBytes(),
        operation.accountBindingBytes(),
        operation.accountBindingDigest(),
        result.canonicalBytes(),
        result.digest());
    return readUnique(application, find(operation));
  }

  public Optional<WorldCanonicalAuthoredGraph> readSynchronized(
      DraftSynchronizedVisibilityEvidence evidence) {
    requireCommittedRead();
    evidence.requireValid();
    var ownerResult = evidence.appliedOwnerResult(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    var rows =
        dsl.fetch(
            selectSql() + " WHERE a.request_id=? OR a.commit_id=?",
            evidence.binding().requestId(),
            evidence.binding().commitId());
    if (rows.isEmpty())
      throw new ConflictException(
          "World synchronized selection has no canonical World APPLIED-result carrier");
    if (rows.size() != 1)
      throw new ConflictException("World synchronized selection has conflicting results");
    Record row = rows.getFirst();
    WorldDraftGraphApplication application = reconstruct(row);
    var result = readExact(application, row);
    if (!evidence
            .request()
            .targetNamespace()
            .equals(application.operation().ownerBinding().targetNamespace())
        || !evidence.binding().equals(application.operation().binding())
        || !ownerResult.resultIdentity().equals(result.resultIdentity())
        || !Arrays.equals(ownerResult.resultBytes(), result.canonicalBytes())
        || !ownerResult.appliedEpochs().equals(result.appliedEpochs())) {
      throw new ConflictException(
          "World synchronized owner result differs from exact canonical retained graph application");
    }
    return Optional.of(
        new WorldCanonicalAuthoredGraphReader().read(application.plan(), result.graphBytes()));
  }

  /**
   * Reads the actual APPLIED carrier for the one exact fresh graph selected for an internal World
   * publication checkpoint. The caller holds the shared OPEN owner lock in the same transaction;
   * this is not a substitute for Game Design selection or authenticated transport evidence.
   */
  WorldDraftGraphAppliedResult readAppliedForPublicationCheckpoint(
      WorldDesignPublicationFenceEvidence freeze, WorldDraftTopologyCommitPlan selectedPlan) {
    Objects.requireNonNull(freeze, "freeze");
    Objects.requireNonNull(selectedPlan, "selectedPlan");
    requireTransaction();
    if (!freeze.ownerBinding().equals(selectedPlan.ownerBinding())
        || selectedPlan.graph().freshGraphDeclaration().isEmpty()) {
      throw new ConflictException(
          "World publication checkpoint requires the exact selected fresh graph owner");
    }
    var binding = selectedPlan.binding();
    var rows =
        dsl.fetch(
            selectSql() + " WHERE a.request_id=? OR a.commit_id=?",
            binding.requestId(),
            binding.commitId());
    if (rows.isEmpty()) {
      throw new ConflictException(
          "World publication checkpoint requires an actual APPLIED graph application");
    }
    if (rows.size() != 1) {
      throw new ConflictException(
          "Selected World graph request and commit identify conflicting applications");
    }
    Record row = rows.getFirst();
    WorldDraftGraphApplication application = reconstruct(row);
    if (!selectedPlan.binding().equals(application.plan().binding())
        || !selectedPlan.ownerBinding().equals(application.plan().ownerBinding())
        || !freeze.ownerBinding().equals(application.operation().ownerBinding())
        || !binding.requestId().equals(application.operation().requestId())
        || !binding.commitId().equals(application.operation().commitId())) {
      throw new ConflictException(
          "World APPLIED graph application differs from the exact selected commit or freeze owner");
    }
    WorldDraftGraphAppliedResult result = readExact(application, row);
    if (!"APPLIED".equals(result.status())) {
      throw new ConflictException("World selected graph application is not APPLIED");
    }
    return result;
  }

  private WorldDraftGraphApplication reconstruct(Record row) {
    try {
      var account =
          DraftAuthorizationFenceBinding.fromStored(row.get("account_binding_bytes", byte[].class));
      var binding =
          DraftCommitBinding.fromStored(
              row.get("binding_json", String.class), row.get("binding_digest", String.class));
      var owner = mapper.readValue(row.get("owner_binding_json", String.class), OwnerBinding.class);
      var operation =
          new WorldDraftTerminalOperation(
              account.operationId(),
              account.requestId(),
              account.commitId(),
              account.fenceId(),
              account.tenantId(),
              account.versionId(),
              binding,
              owner,
              account.canonicalBytes());
      return new WorldDraftGraphApplication(
          operation, WorldDraftTopologyCommitPlan.create(binding, owner));
    } catch (RuntimeException invalid) {
      throw new ConflictException("World application has invalid retained complete operation");
    }
  }

  private WorldDraftGraphAppliedResult readUnique(
      WorldDraftGraphApplication application, List<Record> rows) {
    if (rows.size() != 1)
      throw new ConflictException("World graph application was not stored exactly once");
    return readExact(application, rows.getFirst());
  }

  private WorldDraftGraphAppliedResult readExact(
      WorldDraftGraphApplication application, Record row) {
    var operation = application.operation();
    if (dsl.fetchOne(
            "SELECT 1 FROM world_draft_graph_terminal_identity WHERE operation_id=? AND request_id=? "
                + "AND commit_id=? AND authorization_fence_id=? AND account_binding_bytes=? AND account_binding_digest=? AND outcome='APPLIED'",
            operation.operationId(),
            operation.requestId(),
            operation.commitId(),
            operation.authorizationFenceId(),
            operation.accountBindingBytes(),
            operation.accountBindingDigest())
        == null) {
      throw new ConflictException(
          "World application identity was reused with changed complete binding or terminal identity");
    }
    if (!operation.operationId().equals(row.get("operation_id", UUID.class))
        || !operation.requestId().equals(row.get("request_id", UUID.class))
        || !operation.commitId().equals(row.get("commit_id", UUID.class))
        || !operation.authorizationFenceId().equals(row.get("authorization_fence_id", UUID.class))
        || !Arrays.equals(operation.canonicalBytes(), row.get("operation_bytes", byte[].class))
        || !Arrays.equals(
            operation.accountBindingBytes(), row.get("account_binding_bytes", byte[].class))
        || !operation.accountBindingDigest().equals(row.get("account_binding_digest", String.class))
        || !operation.binding().canonicalJson().equals(row.get("binding_json", String.class))
        || !operation.binding().digest().equals(row.get("binding_digest", String.class))
        || !mapper
            .writeValueAsString(operation.ownerBinding())
            .equals(row.get("owner_binding_json", String.class))
        || !Objects.equals(
            row.get("application_transaction_id", Long.class),
            row.get("graph_transaction_id", Long.class))) {
      throw new ConflictException(
          "World application identity was reused with changed complete binding");
    }
    var original =
        topology.verifyOriginalSource(
            application.plan(),
            row.get("identity_json", String.class),
            row.get("intake_json", String.class));
    if (!Objects.equals(original.localVersionKey(), row.get("local_version_key", Long.class))
        || !Objects.equals(
            original.sourceIntakeReceipt().localTenantKey(),
            row.get("local_tenant_key", Long.class))
        || !operation
            .ownerBinding()
            .targetNamespace()
            .equals(row.get("target_namespace", String.class))
        || !operation.canonicalTenantId().equals(row.get("canonical_tenant_id", UUID.class))
        || !operation.canonicalVersionId().equals(row.get("canonical_version_id", UUID.class))
        || !operation
            .ownerBinding()
            .versionIdentityOperationId()
            .equals(row.get("version_identity_operation_id", UUID.class))
        || !WorldDraftTopologyCommitEvidence.STATUS.equals(
            row.get("storage_status", String.class))) {
      throw new ConflictException(
          "World application differs from retained source/Version association");
    }
    byte[] graph = row.get("graph_bytes", byte[].class);
    if (!WorldDraftGraphAppliedResult.digest(graph)
        .equals("sha256:" + row.get("graph_sha256", String.class))) {
      throw new ConflictException("World retained graph digest differs");
    }
    var decoded =
        topology.verifyImmutableBytes(
            application.plan(), graph, row.get("storage_result_bytes", byte[].class));
    if (decoded.localTenantKey() != original.sourceIntakeReceipt().localTenantKey()
        || decoded.localVersionKey() != original.localVersionKey()) {
      throw new ConflictException("World retained graph differs from original private owner scope");
    }
    for (var graphRow : decoded.rows()) {
      if (dsl.fetchOne(
              "SELECT 1 FROM world_authored_topology_identity WHERE id=? AND private_row_key=? "
                  + "AND family=? AND template_id=? AND request_id=? AND commit_id=? AND tenant_id=? AND version_id=?",
              graphRow.mappingKey(),
              graphRow.privateRowKey(),
              graphRow.template().family().name(),
              graphRow.template().templateId(),
              operation.requestId(),
              operation.commitId(),
              decoded.localTenantKey(),
              decoded.localVersionKey())
          == null) {
        throw new ConflictException("World retained graph differs from immutable original mapping");
      }
    }
    try {
      var startLocation = startLocations.read(application, graph);
      if (application.plan().graph().freshGraphDeclaration().isPresent()
          && startLocation.isEmpty()) {
        throw new ConflictException("World APPLIED result lacks exact start-location evidence");
      }
      return WorldDraftGraphAppliedResult.fromStored(
          application,
          graph,
          row.get("result_bytes", byte[].class),
          row.get("result_digest", String.class),
          startLocation);
    } catch (IllegalArgumentException invalid) {
      throw new ConflictException("World APPLIED result failed exact immutable integrity readback");
    }
  }

  private List<Record> find(WorldDraftTerminalOperation operation) {
    return dsl.fetch(
        selectSql()
            + " WHERE a.operation_id=? OR a.request_id=? OR a.commit_id=? OR a.authorization_fence_id=?",
        operation.operationId(),
        operation.requestId(),
        operation.commitId(),
        operation.authorizationFenceId());
  }

  private static String selectSql() {
    return "SELECT a.*,c.version_identity_operation_id,c.target_namespace,c.canonical_tenant_id,c.canonical_version_id,"
        + "c.local_tenant_key,c.local_version_key,c.owner_binding_json,c.binding_json,c.binding_digest,"
        + "c.graph_bytes,c.graph_sha256,c.storage_status,c.result_bytes AS storage_result_bytes,"
        + "c.application_transaction_id AS graph_transaction_id,to_jsonb(v)::text AS identity_json,"
        + "to_jsonb(i)::text AS intake_json FROM world_draft_graph_application a "
        + "JOIN world_topology_draft_commit c ON c.request_id=a.request_id AND c.commit_id=a.commit_id "
        + "JOIN world_authored_version_identity v ON v.operation_id=c.version_identity_operation_id "
        + "JOIN world_authored_source_intake i ON i.operation_id=v.intake_operation_id";
  }

  private static void requireCommittedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new ConflictException("World applied read requires committed read");
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new ConflictException("Writable World application transaction required");
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
            throw new ConflictException("World application requires READ_COMMITTED");
        });
  }
}
