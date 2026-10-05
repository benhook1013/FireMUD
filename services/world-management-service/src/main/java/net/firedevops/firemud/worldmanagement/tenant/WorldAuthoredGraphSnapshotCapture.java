package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService.WorldDraftDesignDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnerCommitProofStatus;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.OwnerProvenance;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshotRepository.SnapshotConflictException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal-only World graph snapshot capture. Captured rows are representation evidence, not
 * publication eligibility or proof that World applied a complete synchronized owner commit.
 */
public class WorldAuthoredGraphSnapshotCapture {
  private static final int WORLD_DIGEST_SCHEMA_VERSION = 2;

  private static final Table<?> REGION = table("region");
  private static final Table<?> ZONE = table("zone");
  private static final Table<?> ROOM = table("room");
  private static final Table<?> ROOM_EXIT = table("room_exit");
  private static final Table<?> GENERATION_RULE = table("generation_rule");
  private static final Table<?> SPAWN_BINDING = table("world_entity_spawn_binding");
  private static final Table<?> REVISION_LEDGER = table("world_design_revision_ledger");

  private static final Field<Long> ID = field("id", Long.class);
  private static final Field<Long> TENANT_ID = field("tenant_id", Long.class);
  private static final Field<Long> VERSION_ID = field("version_id", Long.class);
  private static final Field<Long> REGION_ID = field("region_id", Long.class);
  private static final Field<Long> ZONE_ID = field("zone_id", Long.class);
  private static final Field<Long> FROM_ROOM_ID = field("from_room_id", Long.class);
  private static final Field<Long> TO_ROOM_ID = field("to_room_id", Long.class);
  private static final Field<Long> ROOM_ID = field("room_id", Long.class);
  private static final Field<Long> GENERATION_SEED = field("generation_seed", Long.class);
  private static final Field<Integer> SHARD_ID = field("shard_id", Integer.class);
  private static final Field<String> NAME = field("name", String.class);
  private static final Field<String> WEATHER = field("weather", String.class);
  private static final Field<String> GENERATOR_TYPE = field("generator_type", String.class);
  private static final Field<String> GENERATOR_PARAMS = field("generator_params", String.class);
  private static final Field<Double> SPACING_MULTIPLIER = field("spacing_multiplier", Double.class);
  private static final Field<String> DESCRIPTION = field("description", String.class);
  private static final Field<String> NAME_LOCALIZED_VARIANTS_JSON =
      field("name_localized_variants_json", String.class);
  private static final Field<String> DESCRIPTION_LOCALIZED_VARIANTS_JSON =
      field("description_localized_variants_json", String.class);
  private static final Field<String> DIRECTION = field("direction", String.class);
  private static final Field<Integer> COST = field("cost", Integer.class);
  private static final Field<String> SCOPE_TYPE = field("scope_type", String.class);
  private static final Field<String> SCOPE_ID = field("scope_id", String.class);
  private static final Field<String> RULE_VALUE = field("value", String.class);
  private static final Field<String> ENTITY_TEMPLATE_TYPE =
      field("entity_template_type", String.class);
  private static final Field<Long> ENTITY_TEMPLATE_ID = field("entity_template_id", Long.class);
  private static final Field<Integer> SPAWN_COUNT = field("spawn_count", Integer.class);
  private static final Field<Integer> RESPAWN_DELAY_SECONDS =
      field("respawn_delay_seconds", Integer.class);

  private static final Field<String> COMMIT_ID = field("commit_id", String.class);
  private static final Field<String> REVISION_ID = field("revision_id", String.class);
  private static final Field<String> OPERATION_TYPE = field("operation_type", String.class);
  private static final Field<String> AGGREGATE_TYPE = field("aggregate_type", String.class);
  private static final Field<String> REQUESTED_AGGREGATE_ID =
      field("requested_aggregate_id", String.class);
  private static final Field<Long> APPLIED_AGGREGATE_ID = field("applied_aggregate_id", Long.class);
  private static final Field<String> RESULT = field("result", String.class);
  private static final Field<Long> AGGREGATE_EPOCH_AFTER =
      field("aggregate_epoch_after", Long.class);
  private static final Field<Long> SCOPE_EPOCH_AFTER = field("scope_epoch_after", Long.class);

  private final DSLContext dsl;
  private final WorldDraftDesignDigestService draftDigestService;
  private final WorldAuthoredGraphSnapshotRepository snapshotRepository;
  private final ObjectMapper objectMapper;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected World collaborators are internal; construction acquires no resources and this class has no finalizer.")
  public WorldAuthoredGraphSnapshotCapture(
      DSLContext dsl,
      WorldDraftDesignDigestService draftDigestService,
      WorldAuthoredGraphSnapshotRepository snapshotRepository,
      ObjectMapper objectMapper) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.draftDigestService =
        Objects.requireNonNull(draftDigestService, "draftDigestService must not be null");
    this.snapshotRepository =
        Objects.requireNonNull(snapshotRepository, "snapshotRepository must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * Captures the current six-family authored graph while holding the exact V25 owner row.
   *
   * <p>The caller owns the writable READ COMMITTED transaction. This method is deliberately unwired
   * from public publication/activation surfaces and persists every result as {@code
   * CAPTURED_UNVERIFIED}; V19 currently lacks the revision-to-complete-scope proof needed to claim
   * synchronized owner application.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public WorldAuthoredGraphSnapshot capture(CaptureRequest request) {
    Objects.requireNonNull(request, "request");
    requireWritableReadCommittedOwnerTransaction();
    OwnerProvenance provenance = snapshotRepository.lockAndResolve(request);
    String tuplesJson = encodeTuples(request.suppliedOwnedAffectedTuples());
    String captureRequestDigest = captureRequestDigest(request, provenance, tuplesJson);

    WorldAuthoredGraphSnapshot prior = snapshotRepository.findByFence(request.publicationFence());
    if (prior != null) {
      snapshotRepository.requireSameRequest(
          prior, request, provenance, captureRequestDigest, tuplesJson);
      return prior;
    }
    if (!"FROZEN".equals(provenance.ownerFreezePhase())) {
      throw new SnapshotConflictException(
          "World graph capture cannot create a new snapshot after its V25 fence leaves FROZEN");
    }

    String revisionEvidenceJson = captureOwnerRevisionEvidence(request, provenance);
    WorldAuthoredGraph graph = readAndValidateGraph(provenance);
    byte[] graphBytes = graph.encode(objectMapper);
    WorldDraftDesignDigest currentDigest =
        draftDigestService.getDraftDesignDigest(
            Long.toString(provenance.localTenantKey()),
            Long.toString(provenance.localVersionKey()));
    if (!Long.toString(provenance.localTenantKey()).equals(currentDigest.tenantId())
        || currentDigest.digestSchemaVersion() != WORLD_DIGEST_SCHEMA_VERSION
        || !request.contentDigest().equals(currentDigest.contentDigest())) {
      throw new SnapshotConflictException(
          "World graph capture differs from the V25 frozen checkpoint under the V27 private Version key");
    }

    WorldAuthoredGraphSnapshot snapshot =
        new WorldAuthoredGraphSnapshot(
            newNonNilUuid(),
            provenance.targetNamespace(),
            provenance.canonicalTenantId(),
            provenance.canonicalVersionId(),
            provenance.versionIdentityOperationId(),
            provenance.worldSlug(),
            provenance.gameDesignVersionId(),
            provenance.localVersionKey(),
            provenance.localTenantKey(),
            (short) 1,
            provenance.intakeOperationId(),
            provenance.intakeRequestId(),
            provenance.intakeRequestDigest(),
            provenance.sourceOperationId(),
            provenance.sourceEvidenceDigest(),
            provenance.intakeReceiptDigest(),
            request.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            request.versionStateEpoch(),
            request.publishWorkflowId(),
            request.appliedCommitId(),
            request.contentDigest(),
            request.digestSchemaVersion(),
            captureRequestDigest,
            tuplesJson,
            revisionEvidenceJson,
            graphBytes,
            sha256(graphBytes),
            OwnerCommitProofStatus.CAPTURED_UNVERIFIED);
    return snapshotRepository.insertAndReadback(snapshot);
  }

  private String captureOwnerRevisionEvidence(CaptureRequest request, OwnerProvenance provenance) {
    Result<? extends Record> rows =
        dsl.select(
                ID,
                COMMIT_ID,
                REVISION_ID,
                OPERATION_TYPE,
                AGGREGATE_TYPE,
                REQUESTED_AGGREGATE_ID,
                APPLIED_AGGREGATE_ID,
                RESULT,
                AGGREGATE_EPOCH_AFTER,
                SCOPE_EPOCH_AFTER)
            .from(REVISION_LEDGER)
            .where(
                TENANT_ID
                    .eq(provenance.localTenantKey())
                    .and(VERSION_ID.eq(provenance.localVersionKey()))
                    .and(COMMIT_ID.eq(request.appliedCommitId())))
            .orderBy(ID.asc())
            .fetch();
    if (rows.isEmpty()) {
      throw new WorldAuthoredGraphSnapshotRepository.MissingOwnerHistoryException(
          "World graph capture requires retained V19 revision history for the named commit");
    }
    List<Map<String, Object>> evidence = new ArrayList<>();
    for (Record row : rows) {
      if (!"APPLIED".equals(row.get(RESULT, String.class))) {
        throw new SnapshotConflictException(
            "World graph capture encountered non-APPLIED V19 revision history");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", required(row, ID));
      item.put("commitId", required(row, COMMIT_ID));
      item.put("revisionId", required(row, REVISION_ID));
      item.put("operationType", required(row, OPERATION_TYPE));
      item.put("aggregateType", required(row, AGGREGATE_TYPE));
      item.put("requestedAggregateId", required(row, REQUESTED_AGGREGATE_ID));
      item.put("appliedAggregateId", required(row, APPLIED_AGGREGATE_ID));
      item.put("result", required(row, RESULT));
      item.put("aggregateEpochAfter", required(row, AGGREGATE_EPOCH_AFTER));
      item.put("scopeEpochAfter", row.get(SCOPE_EPOCH_AFTER, Long.class));
      evidence.add(item);
    }
    return writeJson(evidence);
  }

  private WorldAuthoredGraph readAndValidateGraph(OwnerProvenance provenance) {
    long tenantKey = provenance.localTenantKey();
    long versionKey = provenance.localVersionKey();
    List<Record> regionRows = fetch(REGION, tenantKey, versionKey);
    List<Record> zoneRows = fetch(ZONE, tenantKey, versionKey);
    List<Record> roomRows = fetch(ROOM, tenantKey, versionKey);
    List<Record> exitRows = fetch(ROOM_EXIT, tenantKey, versionKey);
    List<Record> ruleRows = fetch(GENERATION_RULE, tenantKey, versionKey);
    List<Record> bindingRows = fetch(SPAWN_BINDING, tenantKey, versionKey);

    Map<Long, Boolean> regionIds = new HashMap<>();
    Map<Long, Boolean> zoneIds = new HashMap<>();
    Map<Long, Boolean> roomIds = new HashMap<>();
    List<LinkedHashMap<String, Object>> regions = new ArrayList<>();
    List<LinkedHashMap<String, Object>> zones = new ArrayList<>();
    List<LinkedHashMap<String, Object>> rooms = new ArrayList<>();
    List<LinkedHashMap<String, Object>> exits = new ArrayList<>();
    List<LinkedHashMap<String, Object>> rules = new ArrayList<>();
    List<LinkedHashMap<String, Object>> bindings = new ArrayList<>();

    for (Record row : regionRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "region.id");
      unique(regionIds, id, "region");
      Integer shard = required(row, SHARD_ID);
      Long seed = required(row, GENERATION_SEED);
      Double spacing = required(row, SPACING_MULTIPLIER);
      String name = required(row, NAME);
      if (shard < 0 || !Double.isFinite(spacing) || spacing <= 0.0d || name.isBlank()) {
        throw incomplete("region", id, "unsupported required region data");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("shardId", shard);
      item.put("name", name);
      item.put("weather", row.get(WEATHER, String.class));
      item.put("generationSeed", seed);
      item.put("generatorType", row.get(GENERATOR_TYPE, String.class));
      item.put("generatorParams", row.get(GENERATOR_PARAMS, String.class));
      item.put("spacingMultiplier", spacing);
      regions.add(item);
    }

    for (Record row : zoneRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "zone.id");
      unique(zoneIds, id, "zone");
      long regionId = positive(row, REGION_ID, "zone.region_id");
      if (!regionIds.containsKey(regionId)) {
        throw incomplete(
            "zone", id, "region parent is missing or belongs to another tenant/Version");
      }
      String name = required(row, NAME);
      if (name.isBlank()) {
        throw incomplete("zone", id, "zone name is blank");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("regionId", regionId);
      item.put("name", name);
      zones.add(item);
    }

    for (Record row : roomRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "room.id");
      unique(roomIds, id, "room");
      long zoneId = positive(row, ZONE_ID, "room.zone_id");
      if (!zoneIds.containsKey(zoneId)) {
        throw incomplete("room", id, "zone parent is missing or belongs to another tenant/Version");
      }
      String name = required(row, NAME);
      if (name.isBlank()) {
        throw incomplete("room", id, "room name is blank");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("zoneId", zoneId);
      item.put("name", name);
      item.put("description", row.get(DESCRIPTION, String.class));
      item.put("nameLocalizedVariantsJson", row.get(NAME_LOCALIZED_VARIANTS_JSON, String.class));
      item.put(
          "descriptionLocalizedVariantsJson",
          row.get(DESCRIPTION_LOCALIZED_VARIANTS_JSON, String.class));
      rooms.add(item);
    }

    for (Record row : exitRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "room_exit.id");
      long from = positive(row, FROM_ROOM_ID, "room_exit.from_room_id");
      long to = positive(row, TO_ROOM_ID, "room_exit.to_room_id");
      if (!roomIds.containsKey(from) || !roomIds.containsKey(to)) {
        throw incomplete(
            "room_exit", id, "exit endpoint is missing or belongs to another tenant/Version");
      }
      String direction = required(row, DIRECTION);
      Integer cost = required(row, COST);
      if (direction.isBlank() || cost <= 0) {
        throw incomplete("room_exit", id, "unsupported required room-exit data");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("fromRoomId", from);
      item.put("toRoomId", to);
      item.put("direction", direction);
      item.put("cost", cost);
      exits.add(item);
    }

    Set<String> exactRuleKeys = new HashSet<>();
    Set<String> unscopedRuleNames = new HashSet<>();
    for (Record row : ruleRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "generation_rule.id");
      String name = required(row, NAME);
      if (name.isBlank()) {
        throw incomplete("generation_rule", id, "generation rule name is blank");
      }
      String scopeType = row.get(SCOPE_TYPE, String.class);
      String scopeId = row.get(SCOPE_ID, String.class);
      if ((scopeType == null) != (scopeId == null)) {
        throw incomplete(
            "generation_rule", id, "scope_type and scope_id are only partially present");
      }
      String stableScope;
      if (scopeType == null) {
        if (!unscopedRuleNames.add(name)) {
          throw incomplete(
              "generation_rule", id, "duplicate ambiguous unscoped generation rule name");
        }
        stableScope = "";
      } else {
        if (!"REGION_SUBTREE".equals(scopeType) && !"ZONE_SUBTREE".equals(scopeType)) {
          throw incomplete("generation_rule", id, "unsupported generation rule scope type");
        }
        long parsedScopeId = parseCanonicalPositiveId(scopeId, "generation_rule.scope_id", id);
        if (("REGION_SUBTREE".equals(scopeType) && !regionIds.containsKey(parsedScopeId))
            || ("ZONE_SUBTREE".equals(scopeType) && !zoneIds.containsKey(parsedScopeId))) {
          throw incomplete(
              "generation_rule", id, "generation rule scope parent is missing or mismatched");
        }
        stableScope = scopeType + ":" + scopeId;
      }
      if (!exactRuleKeys.add(stableScope + "\u0000" + name)) {
        throw incomplete("generation_rule", id, "duplicate generation rule identity");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("name", name);
      item.put("scopeType", scopeType);
      item.put("scopeId", scopeId);
      item.put("value", row.get(RULE_VALUE, String.class));
      rules.add(item);
    }

    Set<String> spawnKeys = new HashSet<>();
    for (Record row : bindingRows) {
      requireExactScope(row, tenantKey, versionKey);
      long id = positive(row, ID, "world_entity_spawn_binding.id");
      long roomId = positive(row, ROOM_ID, "world_entity_spawn_binding.room_id");
      if (!roomIds.containsKey(roomId)) {
        throw incomplete(
            "world_entity_spawn_binding",
            id,
            "spawn room is missing or belongs to another tenant/Version");
      }
      String entityType = required(row, ENTITY_TEMPLATE_TYPE);
      Long entityId = required(row, ENTITY_TEMPLATE_ID);
      Integer spawnCount = required(row, SPAWN_COUNT);
      Integer delay = required(row, RESPAWN_DELAY_SECONDS);
      if ((!"ITEM".equals(entityType) && !"NPC".equals(entityType))
          || entityId <= 0L
          || spawnCount <= 0
          || delay < 0) {
        throw incomplete(
            "world_entity_spawn_binding", id, "unsupported required spawn binding data");
      }
      String spawnKey = roomId + "\u0000" + entityType + "\u0000" + entityId;
      if (!spawnKeys.add(spawnKey)) {
        throw incomplete("world_entity_spawn_binding", id, "duplicate spawn binding identity");
      }
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("id", id);
      item.put("roomId", roomId);
      item.put("entityTemplateType", entityType);
      item.put("entityTemplateId", entityId);
      item.put("spawnCount", spawnCount);
      item.put("respawnDelaySeconds", delay);
      bindings.add(item);
    }
    return new WorldAuthoredGraph(regions, zones, rooms, exits, rules, bindings);
  }

  private List<Record> fetch(Table<?> table, long tenantKey, long versionKey) {
    List<? extends Record> fetchedRows =
        dsl.selectFrom(table)
            .where(TENANT_ID.eq(tenantKey).and(VERSION_ID.eq(versionKey)))
            .orderBy(ID.asc())
            .fetch();
    List<Record> rows = new ArrayList<>(fetchedRows.size());
    rows.addAll(fetchedRows);
    return rows;
  }

  private void requireExactScope(Record row, long tenantKey, long versionKey) {
    if (!Objects.equals(row.get(TENANT_ID, Long.class), tenantKey)
        || !Objects.equals(row.get(VERSION_ID, Long.class), versionKey)) {
      throw new SnapshotConflictException(
          "World graph row returned outside the exact V27 tenant/Version scope");
    }
  }

  private long positive(Record row, Field<Long> field, String label) {
    Long value = row.get(field, Long.class);
    if (value == null || value <= 0L) {
      throw new SnapshotConflictException(label + " is missing or non-positive");
    }
    return value;
  }

  private long parseCanonicalPositiveId(String value, String label, long rowId) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new NumberFormatException("not canonical positive decimal");
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw incomplete("generation_rule", rowId, label + " is unsupported");
    }
  }

  private String encodeTuples(List<OwnedAffectedTuple> tuples) {
    List<LinkedHashMap<String, Object>> values = new ArrayList<>();
    for (OwnedAffectedTuple tuple : tuples) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("owner", tuple.owner());
      item.put("aggregateType", tuple.aggregateType());
      item.put("aggregateId", tuple.aggregateId());
      item.put("scopeType", tuple.scopeType());
      item.put("scopeId", tuple.scopeId());
      item.put("expectedEpoch", tuple.expectedEpoch());
      values.add(item);
    }
    return writeJson(values);
  }

  private String captureRequestDigest(
      CaptureRequest request, OwnerProvenance provenance, String tuplesJson) {
    LinkedHashMap<String, Object> binding = new LinkedHashMap<>();
    binding.put("targetNamespace", request.targetNamespace());
    binding.put("canonicalTenantId", request.canonicalTenantId().toString());
    binding.put("canonicalVersionId", request.canonicalVersionId().toString());
    binding.put("versionIdentityOperationId", provenance.versionIdentityOperationId().toString());
    binding.put("worldSlug", provenance.worldSlug());
    binding.put("gameDesignVersionId", provenance.gameDesignVersionId());
    binding.put("localVersionKey", provenance.localVersionKey());
    binding.put("localTenantKey", provenance.localTenantKey());
    binding.put("intakeOperationId", provenance.intakeOperationId().toString());
    binding.put("intakeRequestId", request.intakeRequestId().toString());
    binding.put("intakeRequestDigest", provenance.intakeRequestDigest());
    binding.put("sourceOperationId", provenance.sourceOperationId().toString());
    binding.put("sourceEvidenceDigest", provenance.sourceEvidenceDigest());
    binding.put("intakeReceiptDigest", provenance.intakeReceiptDigest());
    binding.put("publicationFence", request.publicationFence().toString());
    binding.put("publicationRequestId", request.publicationRequestId());
    binding.put("requestDigest", request.requestDigest());
    binding.put("versionStateEpoch", request.versionStateEpoch());
    binding.put("publishWorkflowId", request.publishWorkflowId());
    binding.put("appliedCommitId", request.appliedCommitId());
    binding.put("contentDigest", request.contentDigest());
    binding.put("digestSchemaVersion", request.digestSchemaVersion());
    binding.put("suppliedOwnedAffectedTuplesJson", tuplesJson);
    return sha256(writeJsonBytes(binding));
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new SnapshotConflictException("World authored graph evidence could not be encoded");
    }
  }

  private byte[] writeJsonBytes(Object value) {
    try {
      return objectMapper.writeValueAsBytes(value);
    } catch (Exception exception) {
      throw new SnapshotConflictException("World authored graph bytes could not be encoded");
    }
  }

  private <T> T required(Record row, Field<T> field) {
    T value = row.get(field, field.getType());
    if (value == null) {
      throw new SnapshotConflictException(
          "World graph contains null required field " + field.getName());
    }
    return value;
  }

  private SnapshotConflictException incomplete(String family, long id, String reason) {
    return new SnapshotConflictException(
        "World graph capture denied incomplete " + family + " row " + id + ": " + reason);
  }

  private void unique(Map<Long, Boolean> ids, long id, String family) {
    if (ids.putIfAbsent(id, Boolean.TRUE) != null) {
      throw incomplete(family, id, "duplicate row identity");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("World graph capture requires a caller-owned transaction");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("World graph capture requires a writable owner transaction");
    }
    Integer declaredIsolation =
        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    if (declaredIsolation != null && declaredIsolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw new IllegalStateException("World graph capture requires READ COMMITTED isolation");
    }
    Record settings =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS transaction_isolation, "
                + "current_setting('transaction_read_only') AS transaction_read_only");
    if (settings == null
        || !"read committed".equalsIgnoreCase(settings.get("transaction_isolation", String.class))
        || !"off".equalsIgnoreCase(settings.get("transaction_read_only", String.class))) {
      throw new IllegalStateException(
          "World graph capture requires a writable READ COMMITTED owner transaction");
    }
  }

  private static Table<?> table(String name) {
    return DSL.table(DSL.name(name));
  }

  private static <T> Field<T> field(String name, Class<T> type) {
    return DSL.field(DSL.name(name), type);
  }

  private static UUID newNonNilUuid() {
    UUID value;
    do {
      value = UUID.randomUUID();
    } while (value.equals(new UUID(0L, 0L)));
    return value;
  }

  static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder builder = new StringBuilder(digest.length * 2);
      for (byte current : digest) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
