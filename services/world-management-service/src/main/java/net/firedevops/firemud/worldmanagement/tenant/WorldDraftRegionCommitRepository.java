package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.OpenOwner;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Complete region storage over existing owner tables. Immutable graphs are historical evidence,
 * never another mutable graph or a publication checkpoint. This component has no runtime bean.
 */
public class WorldDraftRegionCommitRepository {
  private final DSLContext dsl;
  private final WorldDesignPublicationFenceRepository fence;
  private final WorldAuthoredGraphReader reader;
  private final WorldDraftRegionGraphStager stager;
  private final ObjectMapper mapper;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Construction acquires no resources and this unwired component has no finalizer.")
  public WorldDraftRegionCommitRepository(
      DSLContext dsl, WorldDesignPublicationFenceRepository fence, ObjectMapper mapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    reader = new WorldAuthoredGraphReader(dsl);
    stager = new WorldDraftRegionGraphStager();
  }

  WorldDraftRegionCommitEvidence store(WorldDraftRegionCommitPlan plan) {
    requireTransaction();
    String ownerJson = mapper.writeValueAsString(plan.ownerBinding());
    WorldDraftRegionCommitEvidence replay = find(plan, ownerJson);
    if (replay != null) {
      return replay;
    }
    OpenOwner owner = fence.lockOpenAndResolve(plan.ownerBinding());
    requireSource(plan, owner);
    // First claims and all same-Version writers/freeze share the V25 row. A contender may have
    // completed while this transaction waited, so retry lookup must follow lock acquisition too.
    replay = find(plan, ownerJson);
    if (replay != null) {
      return replay;
    }
    WorldAuthoredGraph before =
        reader.readAndValidateGraph(owner.localTenantKey(), owner.localVersionKey());
    WorldAuthoredGraph staged = stager.stage(plan, before).graph();
    List<Map<String, Object>> changes = changes(plan, staged);
    dsl.resultQuery(
            "SELECT world_store_guarded_region_rows(?::jsonb, ?::jsonb, ?::jsonb)",
            ownerJson,
            plan.binding().canonicalJson(),
            mapper.writeValueAsString(changes))
        .fetch();
    byte[] graphBytes =
        reader.readAndValidateGraph(owner.localTenantKey(), owner.localVersionKey()).encode(mapper);
    if (!Arrays.equals(staged.encode(mapper), graphBytes)) {
      throw new ConflictException("Stored World graph differs from the complete staged operation");
    }
    byte[] resultBytes = encodeResult(plan, graphBytes);
    dsl.execute(
        "INSERT INTO world_region_draft_commit (target_namespace, canonical_tenant_id, canonical_version_id, "
            + "request_id, commit_id, version_identity_operation_id, local_tenant_key, local_version_key, "
            + "owner_binding_json, binding_json, binding_digest, graph_bytes, graph_sha256, result_bytes, storage_status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        plan.ownerBinding().targetNamespace(),
        plan.binding().target().canonicalTenantId(),
        plan.binding().target().canonicalVersionId(),
        plan.binding().requestId(),
        plan.binding().commitId(),
        plan.ownerBinding().versionIdentityOperationId(),
        owner.localTenantKey(),
        owner.localVersionKey(),
        ownerJson,
        plan.binding().canonicalJson(),
        plan.binding().digest(),
        graphBytes,
        sha256(graphBytes),
        resultBytes,
        WorldDraftRegionCommitEvidence.STATUS);
    return new WorldDraftRegionCommitEvidence(
        plan.binding(), plan.ownerBinding(), graphBytes, resultBytes);
  }

  private byte[] encodeResult(WorldDraftRegionCommitPlan plan, byte[] graphBytes) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "1");
    result.put("status", WorldDraftRegionCommitEvidence.STATUS);
    result.put("requestId", plan.binding().requestId().toString());
    result.put("commitId", plan.binding().commitId().toString());
    result.put("bindingDigest", plan.binding().digest());
    result.put("ownerBinding", plan.ownerBinding());
    result.put("graphSha256", sha256(graphBytes));
    result.put(
        "affectedEpochsAfter",
        plan.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit -> {
                  Map<String, String> after = new LinkedHashMap<>();
                  after.put("aggregateType", unit.aggregateType());
                  after.put("aggregateId", unit.aggregateId());
                  after.put("scopeType", unit.scopeType());
                  after.put("scopeId", unit.scopeId());
                  after.put("epoch", Long.toString(Long.parseLong(unit.expectedEpoch()) + 1));
                  return after;
                })
            .toList());
    return mapper.writeValueAsBytes(result);
  }

  private WorldDraftRegionCommitEvidence find(WorldDraftRegionCommitPlan plan, String ownerJson) {
    // Request and commit identities cannot be silently reused under a different canonical target.
    List<Record> rows =
        dsl.resultQuery(
                "SELECT c.*, v.local_tenant_key AS identity_tenant_key FROM world_region_draft_commit c "
                    + "JOIN world_authored_version_identity v ON v.operation_id = c.version_identity_operation_id "
                    + "WHERE c.request_id = ? OR c.commit_id = ?",
                plan.binding().requestId(),
                plan.binding().commitId())
            .fetch();
    if (rows.isEmpty()) {
      return null;
    }
    if (rows.size() != 1) {
      throw new ConflictException("World complete request and commit select conflicting history");
    }
    Record row = rows.get(0);
    DraftCommitBinding binding =
        DraftCommitBinding.fromStored(
            row.get("binding_json", String.class), row.get("binding_digest", String.class));
    if (!binding.equals(plan.binding())
        || !ownerJson.equals(row.get("owner_binding_json", String.class))) {
      throw new ConflictException(
          "World complete request or commit was reused with changed full input or source");
    }
    if (!Objects.equals(
            row.get("target_namespace", String.class), plan.ownerBinding().targetNamespace())
        || !Objects.equals(
            row.get("canonical_tenant_id", java.util.UUID.class),
            binding.target().canonicalTenantId())
        || !Objects.equals(
            row.get("canonical_version_id", java.util.UUID.class),
            binding.target().canonicalVersionId())
        || !Objects.equals(row.get("request_id", java.util.UUID.class), binding.requestId())
        || !Objects.equals(row.get("commit_id", java.util.UUID.class), binding.commitId())
        || !Objects.equals(
            row.get("version_identity_operation_id", java.util.UUID.class),
            plan.ownerBinding().versionIdentityOperationId())
        || !Objects.equals(
            row.get("local_tenant_key", Long.class), row.get("identity_tenant_key", Long.class))) {
      throw new ConflictException(
          "World complete immutable storage history has inconsistent identity provenance");
    }
    byte[] graph = row.get("graph_bytes", byte[].class);
    if (!sha256(graph).equals(row.get("graph_sha256", String.class))
        || !WorldDraftRegionCommitEvidence.STATUS.equals(row.get("storage_status", String.class))) {
      throw new ConflictException("World complete immutable storage history is inconsistent");
    }
    WorldAuthoredGraph.decode(graph, mapper);
    byte[] storedResult = row.get("result_bytes", byte[].class);
    if (!Arrays.equals(encodeResult(plan, graph), storedResult)) {
      throw new ConflictException(
          "World complete immutable result differs from its exact input, epochs or graph");
    }
    return new WorldDraftRegionCommitEvidence(binding, plan.ownerBinding(), graph, storedResult);
  }

  private List<Map<String, Object>> changes(
      WorldDraftRegionCommitPlan plan, WorldAuthoredGraph staged) {
    return plan.regionRevisions().stream()
        .map(
            revision -> {
              long id = Long.parseLong(revision.mutation().getAggregateId());
              Map<String, Object> region =
                  staged.regions().stream()
                      .filter(row -> Objects.equals(row.get("id"), id))
                      .findFirst()
                      .orElseThrow();
              Map<String, Object> change = new LinkedHashMap<>(region);
              change.put(
                  "expectedAggregateEpoch",
                  Long.toString(revision.mutation().getExpectedDraftRevisionEpoch()));
              change.put(
                  "expectedScopeEpoch",
                  Long.toString(revision.mutation().getExpectedDraftScopeRevisionEpoch()));
              return change;
            })
        .toList();
  }

  private void requireSource(WorldDraftRegionCommitPlan plan, OpenOwner owner) {
    AuthoredWorldSourceEvidence source = owner.receipt().source();
    DraftCommitBinding.TargetProof target = plan.binding().target();
    if (target.sourceGameRowId() != source.sourceGameRowId()
        || !target.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !target.sourceProvenanceKind().equals(source.provenanceKind())) {
      throw new ConflictException(
          "World complete commit differs from retained intake source provenance");
    }
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Objects.equals(
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
            java.sql.Connection.TRANSACTION_READ_COMMITTED)) {
      throw new ConflictException(
          "World complete storage requires writable READ COMMITTED transaction");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
