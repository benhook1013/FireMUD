package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftReviewedBaseStaging.StagedDelta;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Unregistered permission-unverified component owner. No Account, COMMIT_ORDER, public route,
 * APPLIED result, synchronized update visibility, release or activation authority is supplied.
 */
final class WorldDraftExistingRegionCommitRepository {
  private final DSLContext dsl;
  private final WorldDesignPublicationFenceRepository fence;
  private final WorldDraftTopologyCommitRepository sources;
  private final ObjectMapper mapper;

  WorldDraftExistingRegionCommitRepository(
      DSLContext dsl, WorldDesignPublicationFenceRepository fence, ObjectMapper mapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    sources = new WorldDraftTopologyCommitRepository(dsl, fence, mapper);
  }

  Optional<WorldDraftExistingRegionStoredEvidence> readCommitted(StagedDelta staged) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("Existing REGION recovery requires committed read");
    }
    return find(staged);
  }

  WorldDraftExistingRegionStoredEvidence store(StagedDelta staged) {
    requireTransaction();
    var prior = find(staged);
    if (prior.isPresent()) return prior.orElseThrow();
    var owner = fence.lockOpenAndResolve(staged.originalOperation().ownerBinding());
    prior = find(staged);
    if (prior.isPresent()) return prior.orElseThrow();
    var plan = staged.delta();
    var binding = staged.originalOperation().binding();
    var execution = executionRevision(plan);
    // PostgreSQL JSONB, trigger-owned OLD/NEW manifests and CAS are intentionally behind one
    // narrow SQL function; generated table DSL cannot express this stored operation.
    Record changed =
        dsl.fetchOne(
            "SELECT world_store_guarded_existing_region(?::jsonb,?::jsonb,?::jsonb)::text AS snapshot",
            WorldDraftTopologyCommitRepository.ownerBindingJson(plan.ownerBinding()),
            binding.canonicalJson(),
            mapper.writeValueAsString(execution));
    if (changed == null)
      throw new ConflictException("Existing REGION mutation returned no snapshot");
    var snapshot = mapper.readTree(changed.get("snapshot", String.class));
    var mapping = snapshot.path("mapping");
    Record actual =
        dsl.fetchOne(
            "SELECT to_jsonb(i)::text AS mapping,to_jsonb(r)::text AS region,"
                + "to_jsonb(a)::text AS aggregate,to_jsonb(s)::text AS scope "
                + "FROM world_authored_topology_identity i "
                + "JOIN region r ON r.id=i.private_row_key AND r.tenant_id=i.tenant_id AND r.version_id=i.version_id "
                + "JOIN world_design_aggregate_epoch a ON a.tenant_id=i.tenant_id AND a.version_id=i.version_id "
                + "AND a.aggregate_type='REGION' AND a.aggregate_id=i.private_row_key "
                + "JOIN world_design_scope_epoch s ON s.tenant_id=i.tenant_id AND s.version_id=i.version_id "
                + "AND s.scope_type='REGION_SUBTREE' AND s.scope_id=i.template_id::text WHERE i.id=?",
            mapping.path("id").asLong());
    if (actual == null
        || !snapshot.path("mapping").equals(mapper.readTree(actual.get("mapping", String.class)))
        || !snapshot.path("newRegion").equals(mapper.readTree(actual.get("region", String.class)))
        || !snapshot
            .path("aggregate")
            .equals(mapper.readTree(actual.get("aggregate", String.class)))
        || !snapshot.path("scope").equals(mapper.readTree(actual.get("scope", String.class)))) {
      throw new ConflictException("Existing REGION independent physical readback differs");
    }
    var stored = WorldDraftExistingRegionStoredEvidence.create(staged, snapshot.toString());
    dsl.execute(
        "INSERT INTO world_region_draft_commit (target_namespace,canonical_tenant_id,canonical_version_id,"
            + "request_id,commit_id,version_identity_operation_id,local_tenant_key,local_version_key,"
            + "owner_binding_json,binding_json,binding_digest,graph_bytes,graph_sha256,result_bytes,storage_status)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        plan.ownerBinding().targetNamespace(),
        binding.target().canonicalTenantId(),
        binding.target().canonicalVersionId(),
        binding.requestId(),
        binding.commitId(),
        plan.ownerBinding().versionIdentityOperationId(),
        owner.localTenantKey(),
        owner.localVersionKey(),
        WorldDraftTopologyCommitRepository.ownerBindingJson(plan.ownerBinding()),
        binding.canonicalJson(),
        binding.digest(),
        stored.graphBytes(),
        WorldDraftGraphAppliedResult.digest(stored.graphBytes()).substring("sha256:".length()),
        stored.resultBytes(),
        WorldDraftExistingRegionStoredEvidence.STATUS);
    return find(staged).orElseThrow();
  }

  private Optional<WorldDraftExistingRegionStoredEvidence> find(StagedDelta staged) {
    WorldDraftExistingRegionStoredEvidence.requireInput(staged);
    var operation = staged.originalOperation();
    var binding = operation.binding();
    var rows =
        dsl.fetch(
            "SELECT c.*,to_jsonb(v)::text AS identity_json,to_jsonb(i)::text AS intake_json "
                + "FROM world_region_draft_commit c "
                + "JOIN world_authored_version_identity v ON v.operation_id=c.version_identity_operation_id "
                + "JOIN world_authored_source_intake i ON i.operation_id=v.intake_operation_id "
                + "WHERE c.request_id=? OR c.commit_id=?",
            binding.requestId(),
            binding.commitId());
    if (rows.isEmpty()) return Optional.empty();
    if (rows.size() != 1)
      throw new ConflictException("Existing REGION identities select conflicting history");
    var row = rows.getFirst();
    var owner = operation.ownerBinding();
    if (!binding.equals(
            DraftCommitBinding.fromStored(
                row.get("binding_json", String.class), row.get("binding_digest", String.class)))
        || !WorldDraftTopologyCommitRepository.ownerBindingJson(owner)
            .equals(row.get("owner_binding_json", String.class))
        || !owner.targetNamespace().equals(row.get("target_namespace", String.class))
        || !owner.canonicalTenantId().equals(row.get("canonical_tenant_id", java.util.UUID.class))
        || !owner.canonicalVersionId().equals(row.get("canonical_version_id", java.util.UUID.class))
        || !binding.requestId().equals(row.get("request_id", java.util.UUID.class))
        || !binding.commitId().equals(row.get("commit_id", java.util.UUID.class))
        || !owner
            .versionIdentityOperationId()
            .equals(row.get("version_identity_operation_id", java.util.UUID.class))
        || !WorldDraftExistingRegionStoredEvidence.STATUS.equals(
            row.get("storage_status", String.class))) {
      throw new ConflictException(
          "Existing REGION request/commit reused with changed complete input");
    }
    var source =
        sources.verifyOriginalSource(
            binding,
            owner,
            row.get("identity_json", String.class),
            row.get("intake_json", String.class));
    if (source.localVersionKey() != row.get("local_version_key", Long.class)
        || source.sourceIntakeReceipt().localTenantKey()
            != row.get("local_tenant_key", Long.class)) {
      throw new ConflictException(
          "Existing REGION stored owner scope differs from retained source");
    }
    byte[] graph = row.get("graph_bytes", byte[].class);
    if (!WorldDraftGraphAppliedResult.digest(graph)
        .equals("sha256:" + row.get("graph_sha256", String.class))) {
      throw new ConflictException("Existing REGION retained snapshot digest differs");
    }
    var snapshot = mapper.readTree(new String(graph, StandardCharsets.UTF_8));
    Record mapping =
        dsl.fetchOne(
            "SELECT to_jsonb(i)::text AS mapping FROM world_authored_topology_identity i WHERE id=?",
            snapshot.path("mapping").path("id").asLong());
    if (mapping == null
        || !snapshot
            .path("mapping")
            .equals(mapper.readTree(mapping.get("mapping", String.class)))) {
      throw new ConflictException("Existing REGION retained creation mapping differs");
    }
    return Optional.of(
        WorldDraftExistingRegionStoredEvidence.fromStored(
            staged, graph, row.get("result_bytes", byte[].class)));
  }

  /**
   * Trusted local projection of the original immutable parsed payload, never public caller input.
   * SQL checks revision identity and owned tuples, not ProtoJSON semantic normalization. The
   * independent physical readback is checked against that original typed plan before history is
   * recorded in the same owner transaction, following the existing fresh-topology boundary.
   */
  static DraftCommitBinding.RevisionPayload executionRevision(
      WorldDraftExistingRegionCommitPlan plan) {
    var original =
        plan.binding().revisions().stream()
            .filter(revision -> revision.owner() == DraftCommitBinding.Owner.WORLD_MANAGEMENT)
            .findFirst()
            .orElseThrow();
    try {
      return new DraftCommitBinding.RevisionPayload(
          original.revisionOrder(),
          original.revisionId(),
          original.owner(),
          JsonFormat.printer().omittingInsignificantWhitespace().print(plan.mutation()));
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("Cannot encode existing REGION typed projection", invalid);
    }
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new ConflictException("Existing REGION requires writable owner transaction");
    }
    dsl.connection(
        connection -> {
          if (connection.getAutoCommit()
              || connection.isReadOnly()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new ConflictException("Existing REGION requires actual READ_COMMITTED");
          }
        });
  }
}
