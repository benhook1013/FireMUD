package net.firedevops.firemud.worldmanagement.tenant;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.OpenOwner;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.Node;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual fresh six-family storage; explicitly unwired, with owner-internal private keys. */
public class WorldDraftTopologyCommitRepository {
  private final DSLContext dsl;
  private final WorldDesignPublicationFenceRepository fence;
  private final ObjectMapper mapper;

  @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "No resources or finalizer.")
  public WorldDraftTopologyCommitRepository(
      DSLContext dsl, WorldDesignPublicationFenceRepository fence, ObjectMapper mapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.fence = Objects.requireNonNull(fence, "fence");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  /** Independent committed reconciliation: absence is UNKNOWN, never definitive abort. */
  public Optional<WorldDraftTopologyCommitEvidence> readCommitted(
      WorldDraftTopologyCommitPlan plan) {
    Objects.requireNonNull(plan, "plan");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException(
          "World complete storage readback requires no active caller transaction");
    }
    return Optional.ofNullable(find(plan, mapper.writeValueAsString(plan.ownerBinding())));
  }

  /**
   * Selects the retained immutable graph for one exact synchronized binding. The separate Game
   * Design evidence must prove this owner is APPLIED; this row's STORED_PERMISSION_UNVERIFIED
   * label is never treated as APPLIED or as authorization. Unlike reconciliation readback, this
   * path intentionally does not consult mutable current topology rows or epochs. Selection stays
   * denied until the owner-result contract canonically binds an APPLIED result to this graph.
   */
  public Optional<WorldCanonicalAuthoredGraph> readSynchronized(
      DraftSynchronizedVisibilityEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    evidence.requireValid();
    var applied =
        evidence.appliedOwnerResult(DraftCommitBinding.Owner.WORLD_MANAGEMENT);
    if (!applied.commitId().equals(evidence.binding().commitId())
        || !applied.bindingDigest().equals(evidence.binding().digest())) {
      throw new ConflictException("World owner APPLIED evidence differs from the exact Draft binding");
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException(
          "World synchronized graph selection requires no active caller transaction");
    }
    throw new ConflictException(
        "World synchronized graph selection has no canonical World APPLIED-result carrier "
            + "bound to the retained graph");
  }

  /** Caller already holds the exact shared V25 FROZEN owner lock; no writer is entered. */
  WorldDraftTopologyCommitEvidence readUnderFrozenLock(WorldDraftTopologyCommitPlan plan) {
    requireTransaction();
    WorldDraftTopologyCommitEvidence stored =
        find(plan, mapper.writeValueAsString(plan.ownerBinding()));
    if (stored == null) {
      throw new ConflictException("World frozen capture has no committed complete topology result");
    }
    return stored;
  }

  /** Validates historical bytes without consulting current content, epochs or owner phase. */
  WorldCanonicalAuthoredGraph verifyImmutableBytes(
      WorldDraftTopologyCommitPlan plan, byte[] graph, byte[] result) {
    WorldCanonicalAuthoredGraph decoded = new WorldCanonicalAuthoredGraphReader().read(plan, graph);
    if (!Arrays.equals(result, encodeResult(plan, graph))) {
      throw new ConflictException("World historical topology result differs from original input");
    }
    return decoded;
  }

  WorldDraftTopologyCommitEvidence store(WorldDraftTopologyCommitPlan plan) {
    requireTransaction();
    String ownerJson = mapper.writeValueAsString(plan.ownerBinding());
    WorldDraftTopologyCommitEvidence prior = find(plan, ownerJson);
    if (prior != null) {
      return prior;
    }
    OpenOwner owner = fence.lockOpenAndResolve(plan.ownerBinding());
    var source = owner.receipt().source();
    var target = plan.binding().target();
    if (target.sourceGameRowId() != source.sourceGameRowId()
        || !target.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !target.sourceProvenanceKind().equals(source.provenanceKind())) {
      throw new ConflictException("World topology differs from retained intake source provenance");
    }
    // Reconcile again after the shared OPEN/freeze lock: an exact first claimant may have won.
    prior = find(plan, ownerJson);
    if (prior != null) {
      return prior;
    }
    dsl.resultQuery(
            "SELECT world_store_guarded_uuid_topology(?::jsonb, ?::jsonb, ?::jsonb)",
            ownerJson,
            plan.binding().canonicalJson(),
            mapper.writeValueAsString(executionRevisions(plan)))
        .fetch();
    byte[] graph = readAndVerifyGraph(plan, owner.localTenantKey(), owner.localVersionKey());
    byte[] result = encodeResult(plan, graph);
    dsl.execute(
        "INSERT INTO world_topology_draft_commit (request_id,commit_id,version_identity_operation_id,"
            + "target_namespace,canonical_tenant_id,canonical_version_id,local_tenant_key,local_version_key,"
            + "owner_binding_json,binding_json,binding_digest,graph_bytes,graph_sha256,result_bytes,storage_status) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        plan.binding().requestId(),
        plan.binding().commitId(),
        plan.ownerBinding().versionIdentityOperationId(),
        plan.ownerBinding().targetNamespace(),
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        owner.localTenantKey(),
        owner.localVersionKey(),
        ownerJson,
        plan.binding().canonicalJson(),
        plan.binding().digest(),
        graph,
        sha256(graph),
        result,
        WorldDraftTopologyCommitEvidence.STATUS);
    return new WorldDraftTopologyCommitEvidence(plan.binding(), plan.ownerBinding(), graph, result);
  }

  /**
   * Trusted local typed projection, like V29's typed component changes. The complete original
   * binding remains unchanged. SQL checks revision identities/order/owner; before storing history,
   * actual rows are independently checked against the original parsed plan, so altered projected
   * semantics roll back the complete transaction. This is never an input from a public caller.
   */
  List<DraftCommitBinding.RevisionPayload> executionRevisions(WorldDraftTopologyCommitPlan plan) {
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    for (Node node : plan.graph().nodes()) {
      try {
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                node.revisionOrder(),
                node.revisionId(),
                DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                JsonFormat.printer().omittingInsignificantWhitespace().print(node.mutation())));
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(
            "World typed execution projection could not be encoded", exception);
      }
    }
    return List.copyOf(revisions);
  }

  private WorldDraftTopologyCommitEvidence find(
      WorldDraftTopologyCommitPlan plan, String ownerJson) {
    List<Record> records =
        dsl.resultQuery(
                "SELECT c.*, to_jsonb(v)::text AS identity_json, "
                    + "to_jsonb(i)::text AS intake_json FROM world_topology_draft_commit c "
                    + "JOIN world_authored_version_identity v ON v.operation_id=c.version_identity_operation_id "
                    + "JOIN world_authored_source_intake i ON i.operation_id=v.intake_operation_id "
                    + "WHERE c.request_id=? OR c.commit_id=?",
                plan.binding().requestId(),
                plan.binding().commitId())
            .fetch();
    if (records.isEmpty()) {
      return null;
    }
    if (records.size() != 1) {
      throw new ConflictException("World complete request and commit select conflicting history");
    }
    Record row = records.getFirst();
    DraftCommitBinding binding =
        DraftCommitBinding.fromStored(
            row.get("binding_json", String.class), row.get("binding_digest", String.class));
    if (!binding.equals(plan.binding())
        || !ownerJson.equals(row.get("owner_binding_json", String.class))) {
      throw new ConflictException(
          "World complete request or commit was reused with changed full input or source");
    }
    WorldAuthoredVersionIdentityReceipt original =
        verifyOriginalSource(
            plan, row.get("identity_json", String.class), row.get("intake_json", String.class));
    long tenant = original.sourceIntakeReceipt().localTenantKey();
    long version = original.localVersionKey();
    var o = plan.ownerBinding();
    if (tenant <= 0
        || version <= 0
        || !Objects.equals(row.get("request_id", UUID.class), binding.requestId())
        || !Objects.equals(row.get("commit_id", UUID.class), binding.commitId())
        || !Objects.equals(row.get("target_namespace", String.class), o.targetNamespace())
        || !Objects.equals(row.get("canonical_tenant_id", UUID.class), o.canonicalTenantId())
        || !Objects.equals(row.get("canonical_version_id", UUID.class), o.canonicalVersionId())
        || !Objects.equals(row.get("local_tenant_key", Long.class), tenant)
        || !Objects.equals(row.get("local_version_key", Long.class), version)
        || !WorldDraftTopologyCommitEvidence.STATUS.equals(
            row.get("storage_status", String.class))) {
      throw new ConflictException("World immutable topology identity provenance is inconsistent");
    }
    byte[] graph = row.get("graph_bytes", byte[].class);
    byte[] result = row.get("result_bytes", byte[].class);
    if (!sha256(graph).equals(row.get("graph_sha256", String.class))
        || !Arrays.equals(graph, readAndVerifyGraph(plan, tenant, version))
        || !Arrays.equals(result, encodeResult(plan, graph))) {
      throw new ConflictException("World immutable topology payload, graph or result differs");
    }
    return new WorldDraftTopologyCommitEvidence(binding, o, graph, result);
  }

  private WorldCanonicalAuthoredGraph findSynchronized(
      DraftCommitBinding requested, String requestedNamespace) {
    List<Record> records =
        dsl.resultQuery(
                "SELECT c.*, to_jsonb(v)::text AS identity_json, "
                    + "to_jsonb(i)::text AS intake_json FROM world_topology_draft_commit c "
                    + "JOIN world_authored_version_identity v ON v.operation_id=c.version_identity_operation_id "
                    + "JOIN world_authored_source_intake i ON i.operation_id=v.intake_operation_id "
                    + "WHERE c.request_id=? OR c.commit_id=?",
                requested.requestId(),
                requested.commitId())
            .fetch();
    if (records.isEmpty()) {
      return null;
    }
    if (records.size() != 1) {
      throw new ConflictException("World synchronized request and commit select conflicting history");
    }
    Record row = records.getFirst();
    DraftCommitBinding stored =
        DraftCommitBinding.fromStored(
            row.get("binding_json", String.class), row.get("binding_digest", String.class));
    if (!stored.equals(requested)) {
      throw new ConflictException("World retained graph does not match the full synchronized binding");
    }

    String ownerJson = row.get("owner_binding_json", String.class);
    OwnerBinding ownerBinding = mapper.readValue(ownerJson, OwnerBinding.class);
    if (!ownerJson.equals(mapper.writeValueAsString(ownerBinding))
        || !requestedNamespace.equals(ownerBinding.targetNamespace())) {
      throw new ConflictException(
          "World retained owner source binding differs from exact JSON or read namespace");
    }
    WorldDraftTopologyCommitPlan plan;
    try {
      plan = WorldDraftTopologyCommitPlan.create(stored, ownerBinding);
    } catch (IllegalArgumentException exception) {
      throw new ConflictException("World retained graph has no valid exact typed binding");
    }
    WorldAuthoredVersionIdentityReceipt original =
        verifyOriginalSource(
            plan, row.get("identity_json", String.class), row.get("intake_json", String.class));
    long tenant = original.sourceIntakeReceipt().localTenantKey();
    long version = original.localVersionKey();
    if (tenant <= 0
        || version <= 0
        || !Objects.equals(row.get("request_id", UUID.class), stored.requestId())
        || !Objects.equals(row.get("commit_id", UUID.class), stored.commitId())
        || !Objects.equals(row.get("target_namespace", String.class), ownerBinding.targetNamespace())
        || !Objects.equals(row.get("canonical_tenant_id", UUID.class), stored.target().canonicalTenantId())
        || !Objects.equals(row.get("canonical_version_id", UUID.class), stored.target().canonicalVersionId())
        || !Objects.equals(row.get("local_tenant_key", Long.class), tenant)
        || !Objects.equals(row.get("local_version_key", Long.class), version)
        || !WorldDraftTopologyCommitEvidence.STATUS.equals(
            row.get("storage_status", String.class))) {
      throw new ConflictException("World immutable synchronized graph provenance is inconsistent");
    }
    byte[] graphBytes = row.get("graph_bytes", byte[].class);
    byte[] resultBytes = row.get("result_bytes", byte[].class);
    if (!sha256(graphBytes).equals(row.get("graph_sha256", String.class))) {
      throw new ConflictException("World retained synchronized graph digest is inconsistent");
    }
    WorldCanonicalAuthoredGraph graph = verifyImmutableBytes(plan, graphBytes, resultBytes);
    if (graph.localTenantKey() != tenant || graph.localVersionKey() != version) {
      throw new ConflictException("World retained graph private selectors differ from source receipt");
    }
    return graph;
  }

  /** Reuses the original closed source and Version evidence validators for detached history. */
  WorldAuthoredVersionIdentityReceipt verifyOriginalSource(
      WorldDraftTopologyCommitPlan plan, String identityJson, String intakeJson) {
    DraftCommitBinding binding = plan.binding();
    JsonNode identity = mapper.readTree(identityJson);
    JsonNode intake = mapper.readTree(intakeJson);
    var o = plan.ownerBinding();
    requireField(identity, "operation_id", o.versionIdentityOperationId());
    requireField(identity, "target_namespace", o.targetNamespace());
    requireField(identity, "canonical_tenant_id", o.canonicalTenantId());
    requireField(identity, "canonical_version_id", o.canonicalVersionId());
    requireField(identity, "game_design_version_id", o.gameDesignVersionId());
    requireField(identity, "intake_operation_id", o.intakeOperationId());
    requireField(identity, "intake_request_id", o.intakeRequestId());
    requireField(identity, "intake_request_digest", o.intakeRequestDigest());
    requireField(identity, "source_operation_id", o.sourceOperationId());
    requireField(identity, "source_evidence_digest", o.sourceEvidenceDigest());
    requireField(identity, "intake_receipt_digest", o.intakeReceiptDigest());
    requireField(intake, "operation_id", o.intakeOperationId());
    requireField(intake, "intake_request_id", o.intakeRequestId());
    requireField(intake, "target_namespace", o.targetNamespace());
    requireField(intake, "canonical_tenant_id", o.canonicalTenantId());
    requireField(intake, "request_digest", o.intakeRequestDigest());
    requireField(intake, "source_operation_id", o.sourceOperationId());
    requireField(intake, "source_evidence_digest", o.sourceEvidenceDigest());
    requireField(intake, "receipt_digest", o.intakeReceiptDigest());
    requireField(intake, "source_game_row_id", binding.target().sourceGameRowId());
    requireField(intake, "source_game_tenant_key", binding.target().sourceGameTenantKey());
    requireField(intake, "source_provenance_kind", binding.target().sourceProvenanceKind());
    long tenant = identity.path("local_tenant_key").asLong();
    long version = identity.path("local_version_key").asLong();
    requireField(intake, "local_tenant_key", tenant);
    // Reuse the existing closed source/receipt validators; these are original local evidence,
    // not a current upstream Version or permission check.
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            intake.path("source_schema_version").asInt(),
            o.targetNamespace(),
            UUID.fromString(intake.path("source_registration_request_id").asText()),
            o.sourceOperationId(),
            intake.path("source_request_digest").asText(),
            o.canonicalTenantId(),
            intake.path("tenant_slug").asText(),
            intake.path("world_slug").asText(),
            intake.path("world_display_name").asText(),
            binding.target().sourceGameRowId(),
            binding.target().sourceGameTenantKey(),
            binding.target().sourceProvenanceKind(),
            o.sourceEvidenceDigest());
    WorldAuthoredSourceIntakeReceipt receipt =
        new WorldAuthoredSourceIntakeReceipt(
            intake.path("schema_version").asInt(),
            o.targetNamespace(),
            o.intakeRequestId(),
            o.intakeOperationId(),
            o.canonicalTenantId(),
            source.worldSlug(),
            o.sourceOperationId(),
            o.sourceEvidenceDigest(),
            o.intakeRequestDigest(),
            o.intakeReceiptDigest(),
            tenant,
            source);
    AuthoredWorldVersionStateEvidence state =
        mapper.readValue(
            identity.path("version_state_evidence_json").asText(),
            AuthoredWorldVersionStateEvidence.class);
    WorldAuthoredVersionIdentityReceipt retainedIdentity =
        new WorldAuthoredVersionIdentityReceipt(
            1, o.versionIdentityOperationId(), version, receipt, state);
    requireField(identity, "version_state_schema_version", state.request().schemaVersion());
    requireField(identity, "version_state_read_request_id", state.request().readRequestId());
    requireField(identity, "version_state_epoch", state.versionStateEpoch());
    requireField(identity, "version_state_evidence_digest", state.evidenceDigest());
    requireField(
        identity,
        "version_state_state",
        state.versionState().name().replace("VERSION_LIFECYCLE_STATE_", ""));
    requireField(identity, "world_slug", source.worldSlug());
    if (!retainedIdentity.canonicalVersionId().equals(o.canonicalVersionId())
        || retainedIdentity.gameDesignVersionId() != o.gameDesignVersionId()) {
      throw new ConflictException(
          "World topology differs from retained closed Version/source evidence");
    }
    return retainedIdentity;
  }

  private byte[] readAndVerifyGraph(WorldDraftTopologyCommitPlan plan, long tenant, long version) {
    List<Record> mappings =
        dsl.resultQuery(
                "SELECT to_jsonb(m)::text AS mapping_json FROM "
                    + "world_authored_topology_identity m WHERE request_id=? ORDER BY revision_order::numeric",
                plan.binding().requestId())
            .fetch();
    if (mappings.size() != plan.graph().nodes().size()) {
      throw new ConflictException("World topology has missing or extra exact UUID mappings");
    }
    for (var type :
        List.of(
            "REGION",
            "ZONE",
            "ROOM",
            "ROOM_EXIT",
            "GENERATION_RULE",
            "WORLD_ENTITY_SPAWN_BINDING")) {
      long expected = plan.graph().nodes().stream().filter(n -> family(n).equals(type)).count();
      Long actual =
          dsl.resultQuery(
                  "SELECT count(*) FROM "
                      + type.toLowerCase(java.util.Locale.ROOT)
                      + " WHERE tenant_id=? AND version_id=?",
                  tenant,
                  version)
              .fetchOne(0, Long.class);
      if (!Objects.equals(actual, expected)) {
        throw new ConflictException("World topology contains extra or missing actual family rows");
      }
    }
    Map<String, Long> keys = new HashMap<>();
    List<JsonNode> identities = new ArrayList<>();
    for (int i = 0; i < mappings.size(); i++) {
      Node node = plan.graph().nodes().get(i);
      JsonNode m = mapper.readTree(mappings.get(i).get("mapping_json", String.class));
      String family = family(node);
      requireField(m, "target_namespace", plan.ownerBinding().targetNamespace());
      requireField(m, "canonical_tenant_id", plan.graph().tenantId());
      requireField(m, "canonical_version_id", plan.graph().versionId());
      requireField(m, "family", family);
      requireField(m, "template_id", node.templateId());
      requireField(m, "tenant_id", tenant);
      requireField(m, "version_id", version);
      requireField(
          m, "version_identity_operation_id", plan.ownerBinding().versionIdentityOperationId());
      requireField(m, "request_id", plan.binding().requestId());
      requireField(m, "commit_id", plan.binding().commitId());
      requireField(m, "revision_id", node.revisionId());
      requireField(m, "revision_order", node.revisionOrder());
      long key = m.path("private_row_key").asLong();
      if (key <= 0
          || m.path("id").asLong() <= 0
          || keys.put(family + ":" + node.templateId(), key) != null) {
        throw new ConflictException("World UUID mapping is not one-to-one");
      }
      identities.add(m);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < identities.size(); i++) {
      Node node = plan.graph().nodes().get(i);
      String family = family(node);
      long key = keys.get(family + ":" + node.templateId());
      // The table name is selected only from the closed protobuf family enumeration.
      Record row =
          dsl.resultQuery(
                  "SELECT to_jsonb(t)::text AS content_json FROM "
                      + family.toLowerCase(java.util.Locale.ROOT)
                      + " t WHERE id=?",
                  key)
              .fetchOne();
      if (row == null) {
        throw new ConflictException("World UUID association has no actual allocated row");
      }
      JsonNode actual = mapper.readTree(row.get("content_json", String.class));
      JsonNode expected = mapper.valueToTree(expectedContent(node, keys, tenant, version));
      if (!sameContent(actual, expected)) {
        throw new ConflictException("World allocated row differs from every exact payload field");
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("mapping", identities.get(i));
      item.put("content", actual);
      rows.add(item);
    }
    for (Node node : plan.graph().nodes()) {
      Long epoch =
          dsl.resultQuery(
                  "SELECT draft_revision_epoch FROM world_design_aggregate_epoch "
                      + "WHERE tenant_id=? AND version_id=? AND aggregate_type=? AND aggregate_id=?",
                  tenant,
                  version,
                  family(node),
                  keys.get(family(node) + ":" + node.templateId()))
              .fetchOne(0, Long.class);
      if (!Objects.equals(epoch, 1L)) {
        throw new ConflictException(
            "World topology aggregate epoch differs from exact stored result");
      }
    }
    Long aggregateCount =
        dsl.resultQuery(
                "SELECT count(*) FROM world_design_aggregate_epoch "
                    + "WHERE tenant_id=? AND version_id=?",
                tenant,
                version)
            .fetchOne(0, Long.class);
    long scopeCountExpected =
        plan.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .filter(u -> !u.scopeType().equals("AGGREGATE"))
            .map(u -> u.scopeType() + ":" + u.scopeId())
            .distinct()
            .count();
    Long scopeCount =
        dsl.resultQuery(
                "SELECT count(*) FROM world_design_scope_epoch "
                    + "WHERE tenant_id=? AND version_id=?",
                tenant,
                version)
            .fetchOne(0, Long.class);
    if (!Objects.equals(aggregateCount, (long) plan.graph().nodes().size())
        || !Objects.equals(scopeCount, scopeCountExpected)) {
      throw new ConflictException("World topology has extra or missing exact epoch fences");
    }
    for (var unit : plan.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT)) {
      if (!unit.scopeType().equals("AGGREGATE")) {
        Long epoch =
            dsl.resultQuery(
                    "SELECT draft_scope_revision_epoch FROM world_design_scope_epoch "
                        + "WHERE tenant_id=? AND version_id=? AND scope_type=? AND scope_id=?",
                    tenant,
                    version,
                    unit.scopeType(),
                    unit.scopeId())
                .fetchOne(0, Long.class);
        if (!Objects.equals(epoch, 1L)) {
          throw new ConflictException(
              "World topology scope epoch differs from exact stored result");
        }
      }
    }
    Map<String, Object> graph = new LinkedHashMap<>();
    graph.put("schemaVersion", "2");
    graph.put("canonicalTenantId", plan.graph().tenantId().toString());
    graph.put("canonicalVersionId", plan.graph().versionId().toString());
    graph.put("rows", rows);
    return mapper.writeValueAsBytes(graph);
  }

  static Map<String, Object> expectedContent(
      Node node, Map<String, Long> keys, long tenant, long version) {
    Map<String, Object> row = new LinkedHashMap<>();
    var m = node.mutation();
    row.put("id", keys.get(family(node) + ":" + node.templateId()));
    row.put("tenant_id", tenant);
    row.put("version_id", version);
    row.put("version", 0);
    switch (m.getAggregateType()) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> {
        var p = m.getRegion();
        row.put("name", p.getName());
        row.put("shard_id", p.getShardId());
        row.put("weather", p.getWeather());
        row.put("generation_seed", p.getGenerationSeed());
        row.put("generator_type", p.getGeneratorType());
        row.put("generator_params", p.getGeneratorParams());
        row.put(
            "spacing_multiplier", p.getSpacingMultiplier() == 0 ? 1.0 : p.getSpacingMultiplier());
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ZONE -> {
        row.put("name", m.getZone().getName());
        row.put("region_id", lookup(keys, "REGION", m.getZone().getRegionId()));
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM -> {
        var p = m.getRoom();
        row.put("name", p.getName());
        row.put("description", p.getDescription());
        row.put("zone_id", lookup(keys, "ZONE", p.getZoneId()));
        row.put("name_localized_variants_json", p.getNameLocalizedVariantsJson());
        row.put("description_localized_variants_json", p.getDescriptionLocalizedVariantsJson());
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> {
        var p = m.getRoomExit();
        row.put("from_room_id", lookup(keys, "ROOM", p.getFromRoomId()));
        row.put("to_room_id", lookup(keys, "ROOM", p.getToRoomId()));
        row.put("direction", p.getDirection());
        row.put("cost", p.getCost() == 0 ? 1 : p.getCost());
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> {
        var p = m.getGenerationRule();
        row.put("name", p.getName());
        row.put("value", p.getValue());
        String scope = m.getScopeType().name().replace("WORLD_DESIGN_SCOPE_TYPE_", "");
        row.put("scope_type", scope);
        row.put(
            "scope_id",
            lookup(
                    keys,
                    scope.equals("REGION_SUBTREE") ? "REGION" : "ZONE",
                    node.scopeId().toString())
                .toString());
      }
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING -> {
        var p = m.getWorldEntitySpawnBinding();
        var e = node.entityReference();
        row.put("room_id", lookup(keys, "ROOM", p.getRoomId()));
        row.put(
            "entity_template_type", e.kind().name().replace("ENTITY_TEMPLATE_REFERENCE_TYPE_", ""));
        row.put("entity_template_id", null);
        row.put("entity_canonical_tenant_id", e.tenantId().toString());
        row.put("entity_canonical_version_id", e.versionId().toString());
        row.put("entity_canonical_template_id", e.templateId().toString());
        row.put("spawn_count", p.getSpawnCount() == 0 ? 1 : p.getSpawnCount());
        row.put("respawn_delay_seconds", p.getRespawnDelaySeconds());
      }
      default -> throw new ConflictException("Unsupported exact World family");
    }
    return row;
  }

  private static Long lookup(Map<String, Long> keys, String family, String id) {
    return Objects.requireNonNull(
        keys.get(family + ":" + UUID.fromString(id)), "typed parent mapping");
  }

  static String family(Node node) {
    return node.mutation().getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
  }

  private void requireField(JsonNode node, String name, Object expected) {
    if (!node.hasNonNull(name) || !node.path(name).asText().equals(expected.toString())) {
      throw new ConflictException("World immutable topology provenance differs at " + name);
    }
  }

  static boolean sameContent(JsonNode actual, JsonNode expected) {
    if (actual.size() != expected.size()) {
      return false;
    }
    for (var entry : expected.properties()) {
      JsonNode value = actual.get(entry.getKey());
      JsonNode required = entry.getValue();
      if (value == null
          || (value.isNumber() && required.isNumber()
              ? value.decimalValue().compareTo(required.decimalValue()) != 0
              : !value.equals(required))) {
        return false;
      }
    }
    return true;
  }

  private byte[] encodeResult(WorldDraftTopologyCommitPlan plan, byte[] graph) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "2");
    result.put("status", WorldDraftTopologyCommitEvidence.STATUS);
    result.put("requestId", plan.binding().requestId().toString());
    result.put("commitId", plan.binding().commitId().toString());
    result.put("bindingDigest", plan.binding().digest());
    result.put("ownerBinding", plan.ownerBinding());
    result.put("graphSha256", sha256(graph));
    result.put(
        "affectedEpochsAfter",
        plan.binding().affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                u -> {
                  Map<String, String> after = new LinkedHashMap<>();
                  after.put("aggregateType", u.aggregateType());
                  after.put("aggregateId", u.aggregateId());
                  after.put("scopeType", u.scopeType());
                  after.put("scopeId", u.scopeId());
                  after.put("epoch", "1");
                  return after;
                })
            .toList());
    return mapper.writeValueAsBytes(result);
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

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
