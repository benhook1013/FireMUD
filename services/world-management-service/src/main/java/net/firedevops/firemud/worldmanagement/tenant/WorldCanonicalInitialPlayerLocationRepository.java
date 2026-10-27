package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.entity.CanonicalPlayerCreatedRuntimeReferenceEvidence;
import net.firedevops.firemud.entitymanagement.v1.PlayerCreatedActorOrigin;
import java.util.Map;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manually composed World-local storage for one canonical character's first location.
 *
 * <p>This repository is deliberately not a Spring component or a transport boundary. A caller must
 * authenticate and validate the Entity assignment and Game Session terminal before calling it. The
 * stored tuple and the returned bytes preserve that caller input; they do not establish that either
 * remote owner authenticated it. The owner transaction independently rechecks the current World
 * lifecycle, selected ROOM mapping, and retained committed initial-admission hold.
 */
public final class WorldCanonicalInitialPlayerLocationRepository {
  private static final String REQUEST_SCHEMA = "world-canonical-initial-player-location-request/v1";
  private static final String RESULT_SCHEMA = "world-canonical-initial-player-location-result/v1";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "activeLifecycleEvidence",
          "canonicalAccountId",
          "canonicalGameInstanceId",
          "canonicalTenantId",
          "catalogRevision",
          "characterId",
          "entityAssignmentDigest",
          "entityAssignmentOperationId",
          "initialAdmissionHoldFence",
          "initialAdmissionHoldId",
          "initialAdmissionOrigin",
          "initialAdmissionOwnerProofDigest",
          "initialAdmissionOwnerProofId",
          "initialAdmissionRequestDigest",
          "initialAdmissionRequestId",
          "operationId",
          "playableStateNamespaceId",
          "playableStateScope",
          "pointerAuditId",
          "pointerVersion",
          "realmId",
          "schema",
          "worldSlug");
  private static final String CREATED_REQUEST_SCHEMA = "world-canonical-initial-player-location-request/v2";
  private static final Set<String> CREATED_REQUEST_FIELDS = createdRequestFields();
  private static final Set<String> CREATED_ORIGIN_FIELDS = Set.of(
      "targetNamespace", "creationOperationId", "mutationDigest", "terminalId", "terminalSha256");

  private static Set<String> createdRequestFields() {
    var fields = new java.util.HashSet<>(REQUEST_FIELDS);
    fields.remove("entityAssignmentDigest");
    fields.remove("entityAssignmentOperationId");
    fields.add("entityCreatedOrigin");
    return Set.copyOf(fields);
  }

  static Map<String, String> createdOriginJson(PlayerCreatedActorOrigin origin) {
    CanonicalPlayerCreatedRuntimeReferenceEvidence.requireOrigin(origin);
    return Map.of("targetNamespace", origin.getTargetNamespace(),
        "creationOperationId", origin.getCreationOperationUuid(),
        "mutationDigest", origin.getMutationDigest(), "terminalId", origin.getTerminalUuid(),
        "terminalSha256", origin.getTerminalSha256());
  }

  private static PlayerCreatedActorOrigin parseCreatedOrigin(JsonNode node) {
    requireExactFields(node, CREATED_ORIGIN_FIELDS, "World original creation origin");
    return CanonicalPlayerCreatedRuntimeReferenceEvidence.requireOrigin(
        PlayerCreatedActorOrigin.newBuilder()
            .setTargetNamespace(text(node, "targetNamespace"))
            .setCreationOperationUuid(text(node, "creationOperationId"))
            .setMutationDigest(text(node, "mutationDigest"))
            .setTerminalUuid(text(node, "terminalId"))
            .setTerminalSha256(text(node, "terminalSha256")).build());
  }

  private static boolean exactStoredOrigin(Record row, ParsedRequest parsed) {
    if (!Objects.equals(row.get("entity_assignment_operation_id", UUID.class), parsed.entityAssignmentOperationId())
        || !Objects.equals(row.get("entity_assignment_digest", String.class), parsed.entityAssignmentDigest())) return false;
    org.jooq.JSONB stored = row.get("entity_created_origin", org.jooq.JSONB.class);
    if (parsed.entityCreatedOrigin() == null) return stored == null;
    if (stored == null) return false;
    try {
      return parsed.entityCreatedOrigin().equals(parseCreatedOrigin(JSON.readTree(stored.data())));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  // Shared with focused native schema proof; it does not establish positive owner authority.
  static final String INITIAL_REGION_MAPPING_SQL =
      "SELECT o.operation_id,o.world_instance_id AS operation_world_instance_id,"
          + "o.request_digest AS operation_request_digest,"
          + "o.request_bytes,o.original_lifecycle_evidence_bytes,o.result_bytes,o.result_digest,"
          + "l.canonical_tenant_id AS location_tenant_id,l.realm_id AS location_realm_id,"
          + "l.playable_state_namespace_id AS location_namespace_id,"
          + "l.canonical_game_instance_id AS location_game_instance_id,"
          + "l.world_instance_id AS location_world_instance_id,"
          + "l.canonical_account_id,l.character_id,l.entity_assignment_operation_id,"
          + "l.entity_assignment_digest,l.entity_created_origin,l.canonical_version_id,l.active_lifecycle_epoch,"
          + "l.initial_room_template_id,l.initial_runtime_room_instance_id,"
          + "l.runtime_room_instance_id,l.initial_location_operation_id,"
          + "l.initial_location_request_digest,l.initial_lifecycle_evidence_bytes,"
          + "l.initial_lifecycle_evidence_digest,"
          + "w.id AS current_world_instance_id,w.tenant_id AS private_tenant_key,"
          + "w.game_instance_id AS private_game_instance_key,w.status AS current_lifecycle_status,"
          + "w.lifecycle_epoch AS current_lifecycle_epoch,w.row_version AS current_lifecycle_row_version,"
          + "rmi.id AS room_row_id,rmi.room_instance_row_id AS runtime_room_key,"
          + "rmi.template_room_id AS room_template_private_key,rmi.zone_instance_id AS room_zone_row_id,"
          + "rmi.region_instance_id AS room_region_row_id,rmi.tenant_id AS room_tenant_key,"
          + "rmi.game_instance_id AS room_game_instance_key,rt.zone_id AS room_template_zone_key,"
          + "zi.id AS zone_row_id,zi.zone_instance_id AS runtime_zone_key,"
          + "zi.template_zone_id AS zone_template_private_key,zi.region_instance_id AS zone_region_row_id,"
          + "zi.canonical_zone_instance_id,zi.tenant_id AS zone_tenant_key,"
          + "zi.game_instance_id AS zone_game_instance_key,z.region_id AS zone_template_region_key,"
          + "ri.id AS region_row_id,ri.canonical_region_instance_id,ri.operational_region_id,"
          + "ri.tenant_id AS region_tenant_key,ri.game_instance_id AS region_game_instance_key,"
          + "ri.world_instance_id AS region_world_instance_id,"
          + "room_map.id AS room_mapping_id,room_map.world_instance_id AS room_mapping_world_id,"
          + "room_map.canonical_game_instance_id AS room_mapping_game_instance_id,"
          + "room_map.template_id AS room_mapping_template_id,"
          + "room_map.template_private_row_key AS room_mapping_template_private_key,"
          + "room_map.runtime_row_id AS room_mapping_runtime_row_id,"
          + "room_map.runtime_room_instance_id AS room_mapping_runtime_room_key,"
          + "zone_map.id AS zone_mapping_id,zone_map.world_instance_id AS zone_mapping_world_id,"
          + "zone_map.canonical_game_instance_id AS zone_mapping_game_instance_id,"
          + "zone_map.template_id AS zone_mapping_template_id,"
          + "zone_map.template_private_row_key AS zone_mapping_template_private_key,"
          + "zone_map.runtime_row_id AS zone_mapping_runtime_row_id,"
          + "zone_map.runtime_identity AS zone_mapping_runtime_identity,"
          + "region_map.id AS region_mapping_id,region_map.world_instance_id AS region_mapping_world_id,"
          + "region_map.canonical_game_instance_id AS region_mapping_game_instance_id,"
          + "region_map.template_id AS region_mapping_template_id,"
          + "region_map.template_private_row_key AS region_mapping_template_private_key,"
          + "region_map.runtime_row_id AS region_mapping_runtime_row_id,"
          + "region_map.runtime_identity AS region_mapping_runtime_identity "
          + "FROM world_canonical_initial_player_location_operation o "
          + "JOIN character_location l ON l.initial_location_operation_id=o.operation_id "
          + "JOIN world_instance w ON w.id=o.world_instance_id "
          + "AND w.id=l.world_instance_id AND w.canonical_game_instance_id=l.canonical_game_instance_id "
          + "JOIN room_instance rmi ON rmi.tenant_id=w.tenant_id "
          + "AND rmi.game_instance_id=w.game_instance_id "
          + "AND rmi.room_instance_row_id=l.runtime_room_instance_id "
          + "JOIN room rt ON rt.id=rmi.template_room_id AND rt.tenant_id=w.tenant_id "
          + "AND rt.version_id=w.version_id "
          + "JOIN zone_instance zi ON zi.id=rmi.zone_instance_id "
          + "AND zi.tenant_id=w.tenant_id AND zi.game_instance_id=w.game_instance_id "
          + "AND zi.region_instance_id=rmi.region_instance_id "
          + "JOIN zone z ON z.id=zi.template_zone_id AND z.id=rt.zone_id "
          + "AND z.tenant_id=w.tenant_id AND z.version_id=w.version_id "
          + "JOIN region_instance ri ON ri.id=zi.region_instance_id "
          + "AND ri.id=rmi.region_instance_id AND ri.world_instance_id=w.id "
          + "AND ri.tenant_id=w.tenant_id AND ri.game_instance_id=w.game_instance_id "
          + "JOIN region template_region ON template_region.id=z.region_id "
          + "AND template_region.tenant_id=w.tenant_id AND template_region.version_id=w.version_id "
          + "JOIN world_canonical_instance_topology_identity room_map "
          + "ON room_map.world_instance_id=w.id AND room_map.canonical_game_instance_id=w.canonical_game_instance_id "
          + "AND room_map.family='ROOM' AND room_map.runtime_row_id=rmi.id "
          + "AND room_map.runtime_room_instance_id=rmi.room_instance_row_id "
          + "AND room_map.template_id=? AND room_map.template_private_row_key=rmi.template_room_id "
          + "JOIN world_canonical_instance_topology_identity zone_map "
          + "ON zone_map.world_instance_id=w.id AND zone_map.canonical_game_instance_id=w.canonical_game_instance_id "
          + "AND zone_map.family='ZONE' AND zone_map.runtime_row_id=zi.id "
          + "AND zone_map.template_private_row_key=zi.template_zone_id "
          + "AND zone_map.runtime_identity=zi.canonical_zone_instance_id "
          + "JOIN world_canonical_instance_topology_identity region_map "
          + "ON region_map.world_instance_id=w.id AND region_map.canonical_game_instance_id=w.canonical_game_instance_id "
          + "AND region_map.family='REGION' AND region_map.runtime_row_id=ri.id "
          + "AND region_map.template_private_row_key=template_region.id "
          + "AND region_map.runtime_identity=ri.canonical_region_instance_id "
          + "WHERE o.operation_id=? AND o.outcome='APPLIED' AND l.canonical_tenant_id=? "
          + "AND l.realm_id=? AND l.playable_state_namespace_id=? AND l.canonical_game_instance_id=? "
          + "AND l.canonical_account_id=? AND l.character_id=? "
          + "AND l.entity_assignment_operation_id IS NOT DISTINCT FROM ? AND l.entity_assignment_digest IS NOT DISTINCT FROM ? "
          + "AND l.canonical_version_id=? AND l.active_lifecycle_epoch=? "
          + "AND l.initial_room_template_id=? AND l.initial_runtime_room_instance_id=l.runtime_room_instance_id "
          + "AND l.initial_runtime_room_instance_id=? AND w.status='ACTIVE' AND w.lifecycle_epoch=?";

  private final DSLContext dsl;
  private final TransactionTemplate readTransaction;
  private final TransactionTemplate writeTransaction;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository;

  public WorldCanonicalInitialPlayerLocationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository, "lifecycleRepository");
    PlatformTransactionManager manager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    readTransaction = new TransactionTemplate(manager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);
    writeTransaction = new TransactionTemplate(manager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Stores an exact APPLIED operation and location atomically, then performs an independent
   * retained readback. A missing or mismatched World owner tuple returns UNREGISTERED and writes
   * nothing. Exact retries return the unchanged durable operation result.
   */
  public Result store(Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction("World canonical initial-location store");
    Boolean retained = writeTransaction.execute(status -> storeInOwnerTransaction(request));
    if (!Boolean.TRUE.equals(retained)) return Result.unregistered();
    return read(request)
        .orElseThrow(
            () ->
                new InvalidInitialLocationReadbackException(
                    "World initial-location operation disappeared after owner commit"));
  }

  /** Reads only the exact retained operation and its associated location row, when APPLIED. */
  public Optional<Result> read(Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction("World canonical initial-location result read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              return readInOwnerTransaction(request).orElse(null);
            }));
  }

  /**
   * Reads an operation by its stable identity and compares the caller's complete source selectors
   * with the immutable original V1019 request before returning its retained result. This allows an
   * exact retry to reuse the original lifecycle evidence and request bytes instead of minting a new
   * lifecycle-read correlation.
   */
  public Optional<Result> readByOperationId(UUID operationId, RequestSelector selector) {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(selector, "selector");
    if (!operationId.equals(selector.operationId())) {
      throw new IllegalArgumentException("Initial-location selector operation id differs");
    }
    requireNoActiveTransaction("World canonical initial-location operation read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              Record operation = readOperation(operationId);
              if (operation == null) return null;
              Request retainedRequest =
                  new Request(
                      operationId,
                      required(operation, "request_digest", String.class),
                      required(operation, "request_bytes", byte[].class),
                      required(operation, "original_lifecycle_evidence_bytes", byte[].class));
              ParsedRequest parsed = parseRequest(retainedRequest);
              selector.requireMatches(parsed);
              return readInOwnerTransaction(retainedRequest)
                  .orElseThrow(
                      () ->
                          invalidReadback(
                              "Retained World initial-location operation disappeared during readback"));
            }));
  }

  /**
   * Reads the retained initial placement and its current same-room region consequence in one
   * read-only repeatable-read World owner snapshot. The Entity receipt remains a caller-side
   * authenticated read and must be completed before invoking this method.
   */
  public Optional<RegionResult> readInitialPlayerLocationRegion(RequestSelector selector) {
    Objects.requireNonNull(selector, "selector");
    requireNoActiveTransaction("World canonical initial-location region read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              Record operation = readOperation(selector.operationId());
              if (operation == null) return null;
              Request retainedRequest = retainedRequest(operation, selector.operationId());
              ParsedRequest parsed = parseRequest(retainedRequest);
              selector.requireMatches(parsed);
              Result placement =
                  readInOwnerTransaction(retainedRequest)
                      .orElseThrow(
                          () ->
                              invalidReadback(
                                  "Retained World initial-location operation disappeared during region read"));
              if (placement.outcome() != Outcome.APPLIED
                  || placement.startLocation() == null
                  || placement.runtimeRoomInstanceId() == null) {
                throw invalidReadback(
                    "Initial-location region consequence requires the retained APPLIED placement");
              }

              WorldCanonicalInstanceLifecycleEvidence retainedLifecycle =
                  WorldCanonicalInstanceLifecycleEvidence.fromStored(
                      retainedRequest.originalLifecycleEvidenceBytes());
              WorldCanonicalInstanceLifecycleEvidence currentLifecycle;
              try {
                currentLifecycle =
                    lifecycleRepository
                        .readForCurrentLocationInOwnerTransaction(retainedLifecycle.request())
                        .orElse(null);
              } catch (
                  WorldCanonicalInstanceLifecycleReadRepository.InvalidLifecycleEvidenceException
                      invalid) {
                throw invalidReadback(
                    "Current World lifecycle evidence is inconsistent with initial placement",
                    invalid);
              }
              requireCurrentLifecycle(retainedLifecycle, currentLifecycle, placement, parsed);
              RegionMapping mapping =
                  readInitialRegionMapping(retainedRequest, placement, parsed, currentLifecycle);
              return new RegionResult(
                  placement,
                  mapping.worldInstanceId(),
                  mapping.runtimeRoomInstanceId(),
                  mapping.canonicalRegionInstanceId(),
                  mapping.operationalRegionId(),
                  currentLifecycle);
            }));
  }

  private Request retainedRequest(Record operation, UUID operationId) {
    return new Request(
        operationId,
        required(operation, "request_digest", String.class),
        required(operation, "request_bytes", byte[].class),
        required(operation, "original_lifecycle_evidence_bytes", byte[].class));
  }

  private void requireCurrentLifecycle(
      WorldCanonicalInstanceLifecycleEvidence retained,
      WorldCanonicalInstanceLifecycleEvidence current,
      Result placement,
      ParsedRequest parsed) {
    if (current == null
        || !"ACTIVE".equals(current.lifecycleStatus())
        || current.lifecycleEpoch() != parsed.activeLifecycleEvidence().lifecycleEpoch()
        || !current.request().equals(retained.request())
        || !current.startLocation().equals(placement.startLocation())
        || current.runtimeRoomInstanceId() != placement.runtimeRoomInstanceId()
        || !current.captureId().equals(retained.captureId())
        || !current.graphSha256().equals(retained.graphSha256())
        || !current.preparationInputDigest().equals(retained.preparationInputDigest())
        || !current
            .operationalRegionAssignments()
            .equals(retained.operationalRegionAssignments())) {
      throw invalidReadback(
          "Current World lifecycle, graph, room, or region assignments differ from retained initial placement");
    }
  }

  private RegionMapping readInitialRegionMapping(
      Request retainedRequest,
      Result placement,
      ParsedRequest parsed,
      WorldCanonicalInstanceLifecycleEvidence currentLifecycle) {
    var rows =
        dsl.fetch(
            INITIAL_REGION_MAPPING_SQL,
            placement.startLocation().roomTemplateId(),
            parsed.operationId(),
            parsed.canonicalTenantId(),
            parsed.realmId(),
            parsed.playableStateNamespaceId(),
            parsed.canonicalGameInstanceId(),
            parsed.canonicalAccountId(),
            parsed.characterId(),
            parsed.entityAssignmentOperationId(),
            parsed.entityAssignmentDigest(),
            parsed.canonicalVersionId(),
            parsed.activeLifecycleEvidence().lifecycleEpoch(),
            placement.startLocation().roomTemplateId(),
            placement.runtimeRoomInstanceId(),
            currentLifecycle.lifecycleEpoch());
    if (rows.size() != 1) {
      throw invalidReadback(
          "Current initial room has no unique exact ROOM-to-ZONE-to-REGION mapping");
    }
    Record row = rows.getFirst();
    long worldInstanceId = required(row, "current_world_instance_id", Long.class);
    long runtimeRoomKey = required(row, "runtime_room_key", Long.class);
    UUID canonicalRegionId = required(row, "canonical_region_instance_id", UUID.class);
    UUID mappedCanonicalRegionId = required(row, "region_mapping_runtime_identity", UUID.class);
    UUID operationalRegionId = required(row, "operational_region_id", UUID.class);
    if (!required(row, "operation_id", UUID.class).equals(parsed.operationId())
        || required(row, "operation_world_instance_id", Long.class) != worldInstanceId
        || !required(row, "operation_request_digest", String.class)
            .equals(placement.requestDigest())
        || !Arrays.equals(required(row, "request_bytes", byte[].class), placement.requestBytes())
        || !Arrays.equals(required(row, "result_bytes", byte[].class), placement.resultBytes())
        || !required(row, "result_digest", String.class).equals(placement.resultDigest())
        || !Arrays.equals(
            required(row, "original_lifecycle_evidence_bytes", byte[].class),
            retainedRequest.originalLifecycleEvidenceBytes())
        || !required(row, "location_tenant_id", UUID.class).equals(parsed.canonicalTenantId())
        || !required(row, "location_realm_id", UUID.class).equals(parsed.realmId())
        || !required(row, "location_namespace_id", UUID.class)
            .equals(parsed.playableStateNamespaceId())
        || !required(row, "location_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(row, "location_world_instance_id", Long.class).equals(worldInstanceId)
        || !required(row, "canonical_account_id", UUID.class).equals(parsed.canonicalAccountId())
        || !required(row, "character_id", UUID.class).equals(parsed.characterId())
        || !exactStoredOrigin(row, parsed)
        || !required(row, "canonical_version_id", UUID.class).equals(parsed.canonicalVersionId())
        || required(row, "active_lifecycle_epoch", Long.class) != currentLifecycle.lifecycleEpoch()
        || !required(row, "initial_room_template_id", UUID.class)
            .equals(placement.startLocation().roomTemplateId())
        || required(row, "initial_runtime_room_instance_id", Long.class) != runtimeRoomKey
        || required(row, "runtime_room_instance_id", Long.class) != runtimeRoomKey
        || !required(row, "initial_location_operation_id", UUID.class).equals(parsed.operationId())
        || !required(row, "initial_location_request_digest", String.class)
            .equals(placement.requestDigest())
        || !Arrays.equals(
            required(row, "initial_lifecycle_evidence_bytes", byte[].class),
            retainedRequest.originalLifecycleEvidenceBytes())
        || !required(row, "initial_lifecycle_evidence_digest", String.class)
            .equals(prefixedDigest(retainedRequest.originalLifecycleEvidenceBytes()))
        || required(row, "current_lifecycle_row_version", Long.class)
            != currentLifecycle.rowVersion()
        || !sameLong(row, "private_tenant_key", "room_tenant_key")
        || !sameLong(row, "private_game_instance_key", "room_game_instance_key")
        || !sameLong(row, "private_tenant_key", "zone_tenant_key")
        || !sameLong(row, "private_game_instance_key", "zone_game_instance_key")
        || !sameLong(row, "private_tenant_key", "region_tenant_key")
        || !sameLong(row, "private_game_instance_key", "region_game_instance_key")
        || !sameLong(row, "current_world_instance_id", "region_world_instance_id")
        || required(row, "room_mapping_world_id", Long.class) != worldInstanceId
        || required(row, "zone_mapping_world_id", Long.class) != worldInstanceId
        || required(row, "region_mapping_world_id", Long.class) != worldInstanceId
        || !required(row, "room_mapping_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(row, "zone_mapping_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(row, "region_mapping_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(row, "room_mapping_template_id", UUID.class)
            .equals(placement.startLocation().roomTemplateId())
        || !sameLong(row, "room_mapping_template_private_key", "room_template_private_key")
        || !sameLong(row, "room_mapping_runtime_row_id", "room_row_id")
        || required(row, "room_mapping_runtime_room_key", Long.class) != runtimeRoomKey
        || !sameLong(row, "room_zone_row_id", "zone_row_id")
        || !sameLong(row, "room_region_row_id", "region_row_id")
        || !sameLong(row, "room_template_zone_key", "zone_template_private_key")
        || !sameLong(row, "zone_mapping_template_private_key", "zone_template_private_key")
        || !sameLong(row, "zone_mapping_runtime_row_id", "zone_row_id")
        || !required(row, "zone_mapping_runtime_identity", UUID.class)
            .equals(required(row, "canonical_zone_instance_id", UUID.class))
        || !sameLong(row, "zone_template_region_key", "region_mapping_template_private_key")
        || !sameLong(row, "region_mapping_runtime_row_id", "region_row_id")
        || canonicalRegionId.equals(operationalRegionId)
        || !canonicalRegionId.equals(mappedCanonicalRegionId)
        || !operationalRegionId.equals(
            currentLifecycle.operationalRegionAssignments().get(canonicalRegionId))) {
      throw invalidReadback(
          "Current initial room, runtime topology, and canonical region assignment disagree");
    }
    if (required(row, "room_mapping_id", Long.class) <= 0
        || required(row, "zone_mapping_id", Long.class) <= 0
        || required(row, "region_mapping_id", Long.class) <= 0
        || !"ACTIVE".equals(required(row, "current_lifecycle_status", String.class))
        || required(row, "current_lifecycle_epoch", Long.class)
            != currentLifecycle.lifecycleEpoch()) {
      throw invalidReadback("Current World region consequence has incomplete owner identity");
    }
    return new RegionMapping(
        worldInstanceId, runtimeRoomKey, canonicalRegionId, operationalRegionId);
  }

  private boolean storeInOwnerTransaction(Request request) {
    requireWritableOwnerTransaction();
    lockOperationId(request.operationId());
    Record prior = readOperation(request.operationId());
    if (prior != null) {
      requireSameOperationBinding(prior, request);
      return true;
    }

    ParsedRequest parsed = parseRequest(request);
    WorldCanonicalInstanceLifecycleEvidence currentLifecycle =
        lifecycleRepository
            .readForActivationInOwnerTransaction(parsed.activeLifecycleEvidence().request())
            .orElse(null);
    if (currentLifecycle == null) {
      // A same-operation writer may have committed while this transaction waited for the
      // lifecycle lock. Recheck its global operation key before reporting an unregistered scope.
      prior = readOperation(request.operationId());
      if (prior != null) {
        requireSameOperationBinding(prior, request);
        return true;
      }
      return false;
    }

    // The lifecycle repository locks the world_instance row FOR UPDATE and independently
    // reconstructs the selected ROOM and complete retained World evidence from this transaction.
    // Recheck a concurrent operation only after acquiring that serialization lock.
    prior = readOperation(request.operationId());
    if (prior != null) {
      requireSameOperationBinding(prior, request);
      return true;
    }
    if (!Arrays.equals(
            parsed.activeLifecycleEvidence().canonicalBytes(), currentLifecycle.canonicalBytes())
        || !"ACTIVE".equals(currentLifecycle.lifecycleStatus())
        || currentLifecycle.lifecycleEpoch() != parsed.activeLifecycleEvidence().lifecycleEpoch()
        || !matchesWorldScope(parsed, currentLifecycle)
        || !hasExactCommittedInitialAdmissionTerminal(parsed, currentLifecycle)) {
      return false;
    }

    Long worldInstanceId =
        worldInstanceId(parsed, currentLifecycle.request().canonicalGameInstanceId());
    if (worldInstanceId == null) return false;

    Record existingLocation =
        dsl.fetchOne(
            "SELECT initial_location_operation_id FROM character_location "
                + "WHERE canonical_tenant_id=? AND playable_state_namespace_id=? "
                + "AND canonical_game_instance_id=? AND character_id=? FOR UPDATE",
            parsed.canonicalTenantId(),
            parsed.playableStateNamespaceId(),
            parsed.canonicalGameInstanceId(),
            parsed.characterId());
    if (existingLocation != null) {
      if (!insertOperation(
          parsed, worldInstanceId, Outcome.CONFLICT, "CHARACTER_ALREADY_PLACED", null, null)) {
        requireConcurrentOperationBinding(request);
      }
      return true;
    }

    if (!insertOperation(
        parsed,
        worldInstanceId,
        Outcome.APPLIED,
        null,
        currentLifecycle.startLocation(),
        currentLifecycle.runtimeRoomInstanceId())) {
      requireConcurrentOperationBinding(request);
      return true;
    }
    insertCharacterLocation(parsed, worldInstanceId, currentLifecycle);
    return true;
  }

  private void requireConcurrentOperationBinding(Request request) {
    Record concurrent = readOperation(request.operationId());
    if (concurrent == null) {
      throw new IllegalStateException(
          "World initial-location operation insert conflicted without a retained operation");
    }
    requireSameOperationBinding(concurrent, request);
  }

  private void lockOperationId(UUID operationId) {
    // Operation IDs are global primary keys, while lifecycle locks are scoped to one World row.
    // Serialize on the immutable operation identity first so cross-scope duplicates cannot race
    // into an unregistered return before the winning operation becomes visible.
    dsl.fetch(
        "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended("
            + "'firemud.world-initial-location:' || ?::text, 0))",
        operationId);
  }

  private Optional<Result> readInOwnerTransaction(Request request) {
    Record operation = readOperation(request.operationId());
    if (operation == null) return Optional.empty();
    requireSameOperationBinding(operation, request);
    ParsedRequest parsed = parseRequest(request);
    String outcomeText = required(operation, "outcome", String.class);
    Long retainedWorldInstanceId = worldInstanceId(parsed, parsed.canonicalGameInstanceId());
    if (retainedWorldInstanceId == null
        || !required(operation, "operation_id", UUID.class).equals(parsed.operationId())
        || !required(operation, "canonical_tenant_id", UUID.class)
            .equals(parsed.canonicalTenantId())
        || !required(operation, "playable_state_namespace_id", UUID.class)
            .equals(parsed.playableStateNamespaceId())
        || !required(operation, "canonical_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(operation, "world_instance_id", Long.class).equals(retainedWorldInstanceId)) {
      throw invalidReadback(
          "Stored World initial-location operation differs from its retained canonical scope");
    }
    Outcome outcome;
    try {
      outcome = Outcome.valueOf(outcomeText);
    } catch (IllegalArgumentException invalid) {
      throw invalidReadback("Stored World initial-location outcome is unsupported", invalid);
    }
    String conflictCode = operation.get("conflict_code", String.class);
    RoomTemplateRef startLocation = null;
    Long runtimeRoomInstanceId = null;
    if (outcome == Outcome.APPLIED) {
      startLocation = parsed.activeLifecycleEvidence().startLocation();
      runtimeRoomInstanceId = parsed.activeLifecycleEvidence().runtimeRoomInstanceId();
      requireExactLocationReadback(operation, parsed, startLocation, runtimeRoomInstanceId);
    } else {
      if (conflictCode == null
          || !MACHINE_CODE.matcher(conflictCode).matches()
          || locationExistsForOperation(request.operationId())) {
        throw invalidReadback(
            "Stored conflicting World initial-location operation has invalid owner state");
      }
    }
    byte[] resultBytes = required(operation, "result_bytes", byte[].class);
    String resultDigest = required(operation, "result_digest", String.class);
    byte[] expectedResult =
        resultBytes(request, outcome, conflictCode, startLocation, runtimeRoomInstanceId);
    if (!Arrays.equals(resultBytes, expectedResult)
        || !resultDigest.equals(prefixedDigest(resultBytes))) {
      throw invalidReadback(
          "Stored World initial-location result bytes or digest are inconsistent");
    }
    return Optional.of(
        new Result(
            outcome,
            conflictCode,
            startLocation,
            runtimeRoomInstanceId,
            request.requestBytes(),
            request.requestDigest(),
            resultBytes,
            resultDigest,
            true));
  }

  private void requireSameOperationBinding(Record operation, Request request) {
    if (!Arrays.equals(required(operation, "request_bytes", byte[].class), request.requestBytes())
        || !Arrays.equals(
            required(operation, "original_lifecycle_evidence_bytes", byte[].class),
            request.originalLifecycleEvidenceBytes())
        || !Objects.equals(
            required(operation, "request_digest", String.class), request.requestDigest())
        || !required(operation, "request_digest", String.class)
            .equals(prefixedDigest(request.requestBytes()))) {
      throw new OperationBindingConflictException(
          "World initial-location operation ID is already bound to different immutable request evidence");
    }
  }

  private boolean matchesWorldScope(
      ParsedRequest parsed, WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    WorldCanonicalInstanceLifecycleEvidence.Request readRequest = lifecycle.request();
    return parsed.canonicalTenantId().equals(readRequest.canonicalTenantId())
        && parsed.playableStateNamespaceId().equals(readRequest.playableStateNamespaceId())
        && parsed.canonicalGameInstanceId().equals(readRequest.canonicalGameInstanceId())
        && parsed.canonicalVersionId().equals(readRequest.canonicalVersionId())
        && parsed.playableStateScope().equals(readRequest.playableStateScope())
        && parsed.worldSlug().equals(readRequest.worldSlug())
        && parsed.canonicalTenantId().equals(lifecycle.startLocation().tenantId())
        && parsed.canonicalVersionId().equals(lifecycle.startLocation().versionId())
        && parsed.activeLifecycleEvidence().startLocation().equals(lifecycle.startLocation())
        && parsed.activeLifecycleEvidence().runtimeRoomInstanceId()
            == lifecycle.runtimeRoomInstanceId();
  }

  /**
   * Matches the persisted World and terminal hold tuple while the current lifecycle row is locked.
   * The World hold contains authenticated-owner terminal bytes from its existing writer; this
   * repository consumes that retained state and never performs remote I/O in the transaction.
   */
  private boolean hasExactCommittedInitialAdmissionTerminal(
      ParsedRequest parsed, WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    Record row =
        dsl.fetchOne(
            "SELECT a.world_instance_id FROM world_canonical_instance_association a "
                + "JOIN world_instance w ON w.id=a.world_instance_id "
                + "JOIN initial_admission_bind_hold h ON h.hold_id=? "
                + "AND h.tenant_id=w.tenant_id AND h.game_instance_id=w.game_instance_id "
                + "WHERE a.canonical_game_instance_id=? AND a.canonical_tenant_id=? "
                + "AND a.canonical_target_namespace=? AND a.canonical_world_slug=? "
                + "AND a.playable_state_namespace_id=? AND a.playable_state_scope=? "
                + "AND a.canonical_version_id=? AND a.canonical_realm_id=? "
                + "AND a.catalog_revision=? AND a.control_plane_request_id=? "
                + "AND w.canonical_game_instance_id=a.canonical_game_instance_id "
                + "AND w.canonical_tenant_id=a.canonical_tenant_id "
                + "AND w.canonical_target_namespace=a.canonical_target_namespace "
                + "AND w.canonical_world_slug=a.canonical_world_slug "
                + "AND w.playable_state_namespace_id=a.playable_state_namespace_id "
                + "AND w.playable_state_scope=a.playable_state_scope "
                + "AND w.canonical_realm_id=a.canonical_realm_id "
                + "AND w.catalog_revision=a.catalog_revision "
                + "AND w.version_id=h.version_id AND w.status='ACTIVE' AND w.lifecycle_epoch=? "
                + "AND h.realm_uuid=? AND h.playable_state_namespace_uuid=? "
                + "AND h.playable_state_scope=? AND h.active_lifecycle_epoch=? "
                + "AND h.initial_admission_request_id=? AND h.request_digest=? "
                + "AND h.expected_no_prior_pointer IS TRUE AND h.expected_catalog_revision=? "
                + "AND h.status='COMMITTED' AND h.owner_proof_id=? AND h.owner_proof_digest=? "
                + "AND h.owner_pointer_audit_id=? AND h.owner_pointer_version=? "
                + "AND h.hold_fence=?",
            parsed.initialAdmissionHoldId(),
            parsed.canonicalGameInstanceId(),
            parsed.canonicalTenantId(),
            lifecycle.request().targetNamespace(),
            parsed.worldSlug(),
            parsed.playableStateNamespaceId(),
            parsed.playableStateScope(),
            parsed.canonicalVersionId(),
            parsed.realmId(),
            parsed.catalogRevision(),
            lifecycle.request().controlPlaneRequestId(),
            lifecycle.lifecycleEpoch(),
            parsed.realmId(),
            parsed.playableStateNamespaceId(),
            parsed.playableStateScope(),
            lifecycle.lifecycleEpoch(),
            parsed.initialAdmissionRequestId(),
            parsed.initialAdmissionRequestDigest(),
            parsed.catalogRevision(),
            parsed.initialAdmissionOwnerProofId(),
            parsed.initialAdmissionOwnerProofDigest(),
            parsed.pointerAuditId(),
            parsed.pointerVersion(),
            parsed.initialAdmissionHoldFence());
    return row != null;
  }

  private Long worldInstanceId(ParsedRequest parsed, UUID canonicalGameInstanceId) {
    Record row =
        dsl.fetchOne(
            "SELECT world_instance_id FROM world_canonical_instance_association "
                + "WHERE canonical_game_instance_id=? AND canonical_tenant_id=? "
                + "AND playable_state_namespace_id=? AND playable_state_scope=? "
                + "AND canonical_version_id=? AND canonical_realm_id=? AND canonical_world_slug=?",
            canonicalGameInstanceId,
            parsed.canonicalTenantId(),
            parsed.playableStateNamespaceId(),
            parsed.playableStateScope(),
            parsed.canonicalVersionId(),
            parsed.realmId(),
            parsed.worldSlug());
    return row == null ? null : required(row, "world_instance_id", Long.class);
  }

  private boolean insertOperation(
      ParsedRequest parsed,
      long worldInstanceId,
      Outcome outcome,
      String conflictCode,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId) {
    byte[] result =
        resultBytes(parsed.request(), outcome, conflictCode, startLocation, runtimeRoomInstanceId);
    return dsl.execute(
            "INSERT INTO world_canonical_initial_player_location_operation ("
                + "canonical_tenant_id, playable_state_namespace_id, canonical_game_instance_id, "
                + "operation_id, world_instance_id, request_digest, request_bytes, "
                + "original_lifecycle_evidence_bytes, outcome, conflict_code, result_bytes, result_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (operation_id) DO NOTHING",
            parsed.canonicalTenantId(),
            parsed.playableStateNamespaceId(),
            parsed.canonicalGameInstanceId(),
            parsed.operationId(),
            worldInstanceId,
            parsed.request().requestDigest(),
            parsed.request().requestBytes(),
            parsed.request().originalLifecycleEvidenceBytes(),
            outcome.name(),
            conflictCode,
            result,
            prefixedDigest(result))
        == 1;
  }

  private void insertCharacterLocation(
      ParsedRequest parsed,
      long worldInstanceId,
      WorldCanonicalInstanceLifecycleEvidence lifecycle) {
    dsl.execute(
        "INSERT INTO character_location (canonical_tenant_id, realm_id, world_slug, "
            + "playable_state_namespace_id, playable_state_scope, canonical_game_instance_id, "
            + "world_instance_id, canonical_account_id, character_id, entity_assignment_operation_id, "
            + "entity_assignment_digest, canonical_version_id, active_lifecycle_epoch, "
            + "initial_room_template_id, initial_runtime_room_instance_id, runtime_room_instance_id, "
            + "initial_admission_hold_id, initial_admission_hold_fence, initial_admission_request_id, "
            + "initial_admission_request_digest, catalog_revision, initial_admission_owner_proof_id, "
            + "initial_admission_owner_proof_digest, pointer_audit_id, pointer_version, "
            + "initial_location_operation_id, initial_location_request_digest, "
            + "initial_lifecycle_evidence_bytes, initial_lifecycle_evidence_digest, entity_created_origin) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        parsed.canonicalTenantId(),
        parsed.realmId(),
        parsed.worldSlug(),
        parsed.playableStateNamespaceId(),
        parsed.playableStateScope(),
        parsed.canonicalGameInstanceId(),
        worldInstanceId,
        parsed.canonicalAccountId(),
        parsed.characterId(),
        parsed.entityAssignmentOperationId(),
        parsed.entityAssignmentDigest(),
        parsed.canonicalVersionId(),
        lifecycle.lifecycleEpoch(),
        lifecycle.startLocation().roomTemplateId(),
        lifecycle.runtimeRoomInstanceId(),
        lifecycle.runtimeRoomInstanceId(),
        parsed.initialAdmissionHoldId(),
        parsed.initialAdmissionHoldFence(),
        parsed.initialAdmissionRequestId(),
        parsed.initialAdmissionRequestDigest(),
        parsed.catalogRevision(),
        parsed.initialAdmissionOwnerProofId(),
        parsed.initialAdmissionOwnerProofDigest(),
        parsed.pointerAuditId(),
        parsed.pointerVersion(),
        parsed.operationId(),
        parsed.request().requestDigest(),
        parsed.request().originalLifecycleEvidenceBytes(),
        prefixedDigest(parsed.request().originalLifecycleEvidenceBytes()),
        parsed.entityCreatedOrigin() == null ? null : org.jooq.JSONB.valueOf(JSON.writeValueAsString(createdOriginJson(parsed.entityCreatedOrigin()))));
  }

  private void requireExactLocationReadback(
      Record operation,
      ParsedRequest parsed,
      RoomTemplateRef expectedStartLocation,
      long expectedRuntimeRoomInstanceId) {
    Record location =
        dsl.fetchOne(
            "SELECT * FROM character_location WHERE canonical_tenant_id=? "
                + "AND playable_state_namespace_id=? AND canonical_game_instance_id=? "
                + "AND character_id=? AND initial_location_operation_id=?",
            parsed.canonicalTenantId(),
            parsed.playableStateNamespaceId(),
            parsed.canonicalGameInstanceId(),
            parsed.characterId(),
            parsed.operationId());
    Long expectedWorldInstanceId = worldInstanceId(parsed, parsed.canonicalGameInstanceId());
    if (location == null
        || expectedWorldInstanceId == null
        || !required(operation, "operation_id", UUID.class).equals(parsed.operationId())
        || !required(operation, "canonical_tenant_id", UUID.class)
            .equals(parsed.canonicalTenantId())
        || !required(operation, "playable_state_namespace_id", UUID.class)
            .equals(parsed.playableStateNamespaceId())
        || !required(operation, "canonical_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(operation, "world_instance_id", Long.class).equals(expectedWorldInstanceId)
        || !required(operation, "request_digest", String.class)
            .equals(parsed.request().requestDigest())
        || !Arrays.equals(
            required(operation, "request_bytes", byte[].class), parsed.request().requestBytes())
        || !Arrays.equals(
            required(operation, "original_lifecycle_evidence_bytes", byte[].class),
            parsed.request().originalLifecycleEvidenceBytes())
        || !required(location, "canonical_tenant_id", UUID.class).equals(parsed.canonicalTenantId())
        || !required(location, "realm_id", UUID.class).equals(parsed.realmId())
        || !required(location, "world_slug", String.class).equals(parsed.worldSlug())
        || !required(location, "playable_state_namespace_id", UUID.class)
            .equals(parsed.playableStateNamespaceId())
        || !required(location, "playable_state_scope", String.class)
            .equals(parsed.playableStateScope())
        || !required(location, "canonical_game_instance_id", UUID.class)
            .equals(parsed.canonicalGameInstanceId())
        || !required(location, "world_instance_id", Long.class).equals(expectedWorldInstanceId)
        || !required(location, "canonical_account_id", UUID.class)
            .equals(parsed.canonicalAccountId())
        || !required(location, "character_id", UUID.class).equals(parsed.characterId())
        || !exactStoredOrigin(location, parsed)
        || !required(location, "canonical_version_id", UUID.class)
            .equals(parsed.canonicalVersionId())
        || required(location, "active_lifecycle_epoch", Long.class)
            != parsed.activeLifecycleEvidence().lifecycleEpoch()
        || !required(location, "initial_room_template_id", UUID.class)
            .equals(expectedStartLocation.roomTemplateId())
        || required(location, "initial_runtime_room_instance_id", Long.class)
            != expectedRuntimeRoomInstanceId
        || required(location, "runtime_room_instance_id", Long.class)
            != expectedRuntimeRoomInstanceId
        || !required(location, "initial_admission_hold_id", UUID.class)
            .equals(parsed.initialAdmissionHoldId())
        || !required(location, "initial_admission_hold_fence", UUID.class)
            .equals(parsed.initialAdmissionHoldFence())
        || !required(location, "initial_admission_request_id", String.class)
            .equals(parsed.initialAdmissionRequestId())
        || !required(location, "initial_admission_request_digest", String.class)
            .equals(parsed.initialAdmissionRequestDigest())
        || required(location, "catalog_revision", Long.class) != parsed.catalogRevision()
        || !required(location, "initial_admission_owner_proof_id", String.class)
            .equals(parsed.initialAdmissionOwnerProofId())
        || !required(location, "initial_admission_owner_proof_digest", String.class)
            .equals(parsed.initialAdmissionOwnerProofDigest())
        || !required(location, "pointer_audit_id", String.class).equals(parsed.pointerAuditId())
        || required(location, "pointer_version", Long.class) != parsed.pointerVersion()
        || !required(location, "initial_location_operation_id", UUID.class)
            .equals(parsed.operationId())
        || !required(location, "initial_location_request_digest", String.class)
            .equals(parsed.request().requestDigest())
        || !Arrays.equals(
            required(location, "initial_lifecycle_evidence_bytes", byte[].class),
            parsed.request().originalLifecycleEvidenceBytes())
        || !required(location, "initial_lifecycle_evidence_digest", String.class)
            .equals(prefixedDigest(parsed.request().originalLifecycleEvidenceBytes()))) {
      throw invalidReadback(
          "Applied World initial-location result differs from its exact retained location row");
    }
  }

  private boolean locationExistsForOperation(UUID operationId) {
    return dsl.fetchOne(
            "SELECT 1 FROM character_location WHERE initial_location_operation_id=?", operationId)
        != null;
  }

  private Record readOperation(UUID operationId) {
    return dsl.fetchOne(
        "SELECT canonical_tenant_id, playable_state_namespace_id, canonical_game_instance_id, "
            + "operation_id, world_instance_id, request_digest, request_bytes, "
            + "original_lifecycle_evidence_bytes, outcome, conflict_code, result_bytes, result_digest "
            + "FROM world_canonical_initial_player_location_operation WHERE operation_id=?",
        operationId);
  }

  private ParsedRequest parseRequest(Request request) {
    if (!request.requestDigest().equals(prefixedDigest(request.requestBytes()))) {
      throw invalidEvidence("World initial-location request digest differs from its exact bytes");
    }
    try {
      String requestJson = strictUtf8(request.requestBytes());
      JsonNode root = JSON.readTree(requestJson);
      boolean created = CREATED_REQUEST_SCHEMA.equals(text(root, "schema"));
      requireExactFields(root, created ? CREATED_REQUEST_FIELDS : REQUEST_FIELDS, "World initial-location request");
      if (!created && !REQUEST_SCHEMA.equals(text(root, "schema"))) {
        throw invalidEvidence("Unsupported World canonical initial-location request schema");
      }
      if (!Arrays.equals(
          request.requestBytes(), Rfc8785CanonicalJson.canonicalizeUtf8(requestJson))) {
        throw invalidEvidence("World canonical initial-location request is not canonical");
      }
      UUID operationId = uuid(root, "operationId");
      if (!request.operationId().equals(operationId)) {
        throw invalidEvidence("World initial-location operation ID differs from its request bytes");
      }
      if (!"NO_PRIOR_POINTER".equals(text(root, "initialAdmissionOrigin"))) {
        throw invalidEvidence("World initial location requires the exact first-pointer origin");
      }
      byte[] activeEvidenceBytes = canonicalObjectBytes(root.get("activeLifecycleEvidence"));
      WorldCanonicalInstanceLifecycleEvidence activeEvidence =
          WorldCanonicalInstanceLifecycleEvidence.fromStored(activeEvidenceBytes);
      WorldCanonicalInstanceLifecycleEvidence originalEvidence =
          WorldCanonicalInstanceLifecycleEvidence.fromStored(
              request.originalLifecycleEvidenceBytes());
      if (!sameLifecycleExceptReadRequestId(
          activeEvidenceBytes, originalEvidence.canonicalBytes())) {
        throw invalidEvidence(
            "World initial-location request and original lifecycle evidence differ");
      }
      if (!"ACTIVE".equals(activeEvidence.lifecycleStatus())) {
        throw invalidEvidence("Initial placement requires exact ACTIVE World lifecycle evidence");
      }

      UUID canonicalTenantId = uuid(root, "canonicalTenantId");
      UUID namespaceId = uuid(root, "playableStateNamespaceId");
      UUID gameInstanceId = uuid(root, "canonicalGameInstanceId");
      UUID accountId = uuid(root, "canonicalAccountId");
      UUID characterId = uuid(root, "characterId");
      UUID assignmentOperationId = created ? null : uuid(root, "entityAssignmentOperationId");
      String assignmentDigest = created ? null : bareDigest(text(root, "entityAssignmentDigest"));
      PlayerCreatedActorOrigin createdOrigin = created ? parseCreatedOrigin(root.get("entityCreatedOrigin")) : null;
      UUID realmId = uuid(root, "realmId");
      String worldSlug = text(root, "worldSlug");
      String playableStateScope = text(root, "playableStateScope");
      UUID holdId = uuid(root, "initialAdmissionHoldId");
      UUID holdFence = uuid(root, "initialAdmissionHoldFence");
      String admissionRequestId = boundedText(root, "initialAdmissionRequestId", 128);
      String admissionDigest = bareDigest(text(root, "initialAdmissionRequestDigest"));
      String ownerProofId = boundedText(root, "initialAdmissionOwnerProofId", 128);
      String ownerProofDigest = bareDigest(text(root, "initialAdmissionOwnerProofDigest"));
      String pointerAuditId = boundedText(root, "pointerAuditId", 128);
      long catalogRevision = positiveLong(root, "catalogRevision");
      long pointerVersion = positiveLong(root, "pointerVersion");
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest = activeEvidence.request();
      if ((createdOrigin != null && !createdOrigin.getTargetNamespace().equals(lifecycleRequest.targetNamespace()))
          || !canonicalTenantId.equals(lifecycleRequest.canonicalTenantId())
          || !namespaceId.equals(lifecycleRequest.playableStateNamespaceId())
          || !gameInstanceId.equals(lifecycleRequest.canonicalGameInstanceId())
          || !playableStateScope.equals(lifecycleRequest.playableStateScope())
          || !worldSlug.equals(lifecycleRequest.worldSlug())
          || !canonicalTenantId.equals(activeEvidence.startLocation().tenantId())
          || !lifecycleRequest
              .canonicalVersionId()
              .equals(activeEvidence.startLocation().versionId())) {
        throw invalidEvidence(
            "World initial-location request differs from its exact canonical lifecycle scope");
      }
      return new ParsedRequest(
          request,
          operationId,
          canonicalTenantId,
          namespaceId,
          gameInstanceId,
          accountId,
          characterId,
          assignmentOperationId,
          assignmentDigest,
          realmId,
          worldSlug,
          playableStateScope,
          lifecycleRequest.canonicalVersionId(),
          holdId,
          holdFence,
          admissionRequestId,
          admissionDigest,
          catalogRevision,
          ownerProofId,
          ownerProofDigest,
          pointerAuditId,
          pointerVersion,
          activeEvidence,
          createdOrigin);
    } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) {
      throw invalidEvidence("World canonical initial-location request is invalid", invalid);
    } catch (IllegalArgumentException invalid) {
      if (invalid instanceof InvalidInitialLocationEvidenceException evidenceFailure) {
        throw evidenceFailure;
      }
      throw invalidEvidence("World canonical initial-location evidence is invalid", invalid);
    }
  }

  private static byte[] resultBytes(
      Request request,
      Outcome outcome,
      String conflictCode,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId) {
    if (outcome == Outcome.CONFLICT) {
      if (conflictCode == null || !MACHINE_CODE.matcher(conflictCode).matches()) {
        throw new IllegalArgumentException("World initial-location conflict code is invalid");
      }
      return ("{\"conflictCode\":\""
              + conflictCode
              + "\",\"operationId\":\""
              + request.operationId()
              + "\",\"outcome\":\"CONFLICT\",\"requestBytesBase64\":\""
              + Base64.getEncoder().encodeToString(request.requestBytes())
              + "\",\"requestDigest\":\""
              + request.requestDigest()
              + "\",\"runtimeRoomInstanceId\":null,\"schema\":\""
              + RESULT_SCHEMA
              + "\",\"startLocation\":null}")
          .getBytes(StandardCharsets.UTF_8);
    }
    if (conflictCode != null || startLocation == null || runtimeRoomInstanceId == null) {
      throw new IllegalArgumentException("Applied World initial-location result is incomplete");
    }
    return ("{\"conflictCode\":null,\"operationId\":\""
            + request.operationId()
            + "\",\"outcome\":\"APPLIED\",\"requestBytesBase64\":\""
            + Base64.getEncoder().encodeToString(request.requestBytes())
            + "\",\"requestDigest\":\""
            + request.requestDigest()
            + "\",\"runtimeRoomInstanceId\":\""
            + runtimeRoomInstanceId
            + "\",\"schema\":\""
            + RESULT_SCHEMA
            + "\",\"startLocation\":{\"roomTemplateId\":\""
            + startLocation.roomTemplateId()
            + "\",\"tenantId\":\""
            + startLocation.tenantId()
            + "\",\"versionId\":\""
            + startLocation.versionId()
            + "\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] canonicalObjectBytes(JsonNode object) {
    if (object == null || !object.isObject()) {
      throw invalidEvidence("World initial-location active lifecycle evidence must be an object");
    }
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(object));
    } catch (java.io.IOException | tools.jackson.core.JacksonException invalid) {
      throw invalidEvidence("World initial-location lifecycle evidence is invalid", invalid);
    }
  }

  private static boolean sameLifecycleExceptReadRequestId(byte[] first, byte[] second) {
    try {
      JsonNode left = JSON.readTree(strictUtf8(first));
      JsonNode right = JSON.readTree(strictUtf8(second));
      if (!(left.get("request") instanceof ObjectNode leftRequest)
          || !(right.get("request") instanceof ObjectNode rightRequest)) {
        return false;
      }
      leftRequest.remove("readRequestId");
      rightRequest.remove("readRequestId");
      return left.equals(right);
    } catch (tools.jackson.core.JacksonException invalid) {
      throw invalidEvidence("World lifecycle evidence is invalid", invalid);
    }
  }

  private static void requireExactFields(JsonNode object, Set<String> fields, String label) {
    if (object == null || !object.isObject()) throw invalidEvidence(label + " must be an object");
    Set<String> actual = new java.util.HashSet<>();
    object.propertyNames().forEach(actual::add);
    if (!actual.equals(fields)) throw invalidEvidence(label + " has missing or unsupported fields");
  }

  private static String text(JsonNode object, String name) {
    JsonNode value = object.get(name);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw invalidEvidence("World initial-location request is missing " + name);
    }
    return value.textValue();
  }

  private static String boundedText(JsonNode object, String name, int maxLength) {
    String value = text(object, name);
    if (value.length() > maxLength) {
      throw invalidEvidence("World initial-location request field exceeds its limit: " + name);
    }
    return value;
  }

  private static UUID uuid(JsonNode object, String name) {
    String value = text(object, name);
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw invalidEvidence("World initial-location request has a noncanonical " + name);
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      if (invalid instanceof InvalidInitialLocationEvidenceException evidenceFailure) {
        throw evidenceFailure;
      }
      throw invalidEvidence("World initial-location request has an invalid " + name, invalid);
    }
  }

  private static String bareDigest(String value) {
    if (!SHA256.matcher(value).matches()) {
      throw invalidEvidence("World initial-location request has a noncanonical digest");
    }
    return value;
  }

  private static long positiveLong(JsonNode object, String name) {
    String value = text(object, name);
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw invalidEvidence("World initial-location request has an invalid " + name);
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw invalidEvidence("World initial-location request has an invalid " + name, invalid);
    }
  }

  private static String prefixedDigest(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(sha256(bytes));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw invalidEvidence("World initial-location evidence is not valid UTF-8", invalid);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null)
      throw invalidReadback("Stored World initial-location row is missing " + field);
    return value;
  }

  private static boolean sameLong(Record row, String left, String right) {
    return required(row, left, Long.class).longValue()
        == required(row, right, Long.class).longValue();
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " requires an independent owner transaction");
    }
  }

  private void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World initial-location readback requires a read-only repeatable-read transaction");
    }
    Record state =
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only");
    if (state == null
        || !"repeatable read".equals(state.get("isolation", String.class))
        || !"on".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "World initial-location readback requires a read-only repeatable-read transaction");
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "World initial-location write requires a writable World owner transaction");
    }
  }

  private static InvalidInitialLocationEvidenceException invalidEvidence(String message) {
    return new InvalidInitialLocationEvidenceException(message);
  }

  private static InvalidInitialLocationEvidenceException invalidEvidence(
      String message, Throwable cause) {
    return new InvalidInitialLocationEvidenceException(message, cause);
  }

  private static InvalidInitialLocationReadbackException invalidReadback(String message) {
    return new InvalidInitialLocationReadbackException(message);
  }

  private static InvalidInitialLocationReadbackException invalidReadback(
      String message, Throwable cause) {
    return new InvalidInitialLocationReadbackException(message, cause);
  }

  public enum Outcome {
    APPLIED,
    CONFLICT,
    UNREGISTERED
  }

  /** Fresh region consequence of one retained APPLIED initial placement. */
  public record RegionResult(
      Result placementResult,
      long privateWorldInstanceKey,
      long runtimeRoomInstanceKey,
      UUID canonicalRegionInstanceId,
      UUID operationalRegionId,
      WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence) {
    public RegionResult {
      Objects.requireNonNull(placementResult, "placementResult");
      Objects.requireNonNull(canonicalRegionInstanceId, "canonicalRegionInstanceId");
      Objects.requireNonNull(operationalRegionId, "operationalRegionId");
      Objects.requireNonNull(lifecycleEvidence, "lifecycleEvidence");
      if (placementResult.outcome() != Outcome.APPLIED
          || privateWorldInstanceKey <= 0
          || runtimeRoomInstanceKey <= 0
          || placementResult.runtimeRoomInstanceId() == null
          || placementResult.runtimeRoomInstanceId() != runtimeRoomInstanceKey
          || canonicalRegionInstanceId.equals(operationalRegionId)
          || !operationalRegionId.equals(
              lifecycleEvidence.operationalRegionAssignments().get(canonicalRegionInstanceId))) {
        throw new IllegalArgumentException(
            "World initial-location region consequence is inconsistent");
      }
    }
  }

  private record RegionMapping(
      long worldInstanceId,
      long runtimeRoomInstanceId,
      UUID canonicalRegionInstanceId,
      UUID operationalRegionId) {}

  /** Exact original owner request and lifecycle proof bytes; the tuple is comparison input only. */
  public record Request(
      UUID operationId,
      String requestDigest,
      byte[] requestBytes,
      byte[] originalLifecycleEvidenceBytes) {
    public Request {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(requestDigest, "requestDigest");
      requestBytes = Objects.requireNonNull(requestBytes, "requestBytes").clone();
      originalLifecycleEvidenceBytes =
          Objects.requireNonNull(originalLifecycleEvidenceBytes, "originalLifecycleEvidenceBytes")
              .clone();
      if (operationId.equals(new UUID(0L, 0L)) || requestBytes.length == 0) {
        throw new IllegalArgumentException("World initial-location request identity is invalid");
      }
    }

    @Override
    public byte[] requestBytes() {
      return requestBytes.clone();
    }

    @Override
    public byte[] originalLifecycleEvidenceBytes() {
      return originalLifecycleEvidenceBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof Request that
              && operationId.equals(that.operationId)
              && requestDigest.equals(that.requestDigest)
              && Arrays.equals(requestBytes, that.requestBytes)
              && Arrays.equals(
                  originalLifecycleEvidenceBytes, that.originalLifecycleEvidenceBytes));
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(operationId, requestDigest);
      result = 31 * result + Arrays.hashCode(requestBytes);
      return 31 * result + Arrays.hashCode(originalLifecycleEvidenceBytes);
    }
  }

  /** Complete immutable source and target fields checked against a retained V1019 operation. */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "Generated protobuf creation origins are immutable values")
  public record RequestSelector(
      UUID operationId,
      UUID canonicalTenantId,
      UUID playableStateNamespaceId,
      UUID canonicalGameInstanceId,
      UUID canonicalAccountId,
      UUID characterId,
      UUID entityAssignmentOperationId,
      String entityAssignmentDigest,
      UUID realmId,
      String worldSlug,
      String playableStateScope,
      UUID canonicalVersionId,
      UUID initialAdmissionHoldId,
      UUID initialAdmissionHoldFence,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      long activeLifecycleEpoch,
      long catalogRevision,
      String initialAdmissionOwnerProofId,
      String initialAdmissionOwnerProofDigest,
      String pointerAuditId,
      long pointerVersion,
      PlayerCreatedActorOrigin entityCreatedOrigin) {
    public RequestSelector {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(canonicalGameInstanceId, "canonicalGameInstanceId");
      Objects.requireNonNull(canonicalAccountId, "canonicalAccountId");
      Objects.requireNonNull(characterId, "characterId");
      if (entityCreatedOrigin == null) {
        Objects.requireNonNull(entityAssignmentOperationId, "entityAssignmentOperationId");
        Objects.requireNonNull(entityAssignmentDigest, "entityAssignmentDigest");
      } else {
        CanonicalPlayerCreatedRuntimeReferenceEvidence.requireOrigin(entityCreatedOrigin);
        if (entityAssignmentOperationId != null || entityAssignmentDigest != null) {
          throw new IllegalArgumentException("Exactly one actor origin is required");
        }
      }
      Objects.requireNonNull(realmId, "realmId");
      Objects.requireNonNull(worldSlug, "worldSlug");
      Objects.requireNonNull(playableStateScope, "playableStateScope");
      Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
      Objects.requireNonNull(initialAdmissionHoldId, "initialAdmissionHoldId");
      Objects.requireNonNull(initialAdmissionHoldFence, "initialAdmissionHoldFence");
      Objects.requireNonNull(initialAdmissionRequestId, "initialAdmissionRequestId");
      Objects.requireNonNull(initialAdmissionRequestDigest, "initialAdmissionRequestDigest");
      Objects.requireNonNull(initialAdmissionOwnerProofId, "initialAdmissionOwnerProofId");
      Objects.requireNonNull(initialAdmissionOwnerProofDigest, "initialAdmissionOwnerProofDigest");
      Objects.requireNonNull(pointerAuditId, "pointerAuditId");
      if (activeLifecycleEpoch <= 0 || catalogRevision <= 0 || pointerVersion <= 0) {
        throw new IllegalArgumentException("Initial-location selector counters must be positive");
      }
    }

    private void requireMatches(ParsedRequest parsed) {
      if (!operationId.equals(parsed.operationId())
          || !canonicalTenantId.equals(parsed.canonicalTenantId())
          || !playableStateNamespaceId.equals(parsed.playableStateNamespaceId())
          || !canonicalGameInstanceId.equals(parsed.canonicalGameInstanceId())
          || !canonicalAccountId.equals(parsed.canonicalAccountId())
          || !characterId.equals(parsed.characterId())
          || !Objects.equals(entityAssignmentOperationId, parsed.entityAssignmentOperationId())
          || !Objects.equals(entityAssignmentDigest, parsed.entityAssignmentDigest())
          || !Objects.equals(entityCreatedOrigin, parsed.entityCreatedOrigin())
          || !realmId.equals(parsed.realmId())
          || !worldSlug.equals(parsed.worldSlug())
          || !playableStateScope.equals(parsed.playableStateScope())
          || !canonicalVersionId.equals(parsed.canonicalVersionId())
          || !initialAdmissionHoldId.equals(parsed.initialAdmissionHoldId())
          || !initialAdmissionHoldFence.equals(parsed.initialAdmissionHoldFence())
          || !initialAdmissionRequestId.equals(parsed.initialAdmissionRequestId())
          || !initialAdmissionRequestDigest.equals(parsed.initialAdmissionRequestDigest())
          || activeLifecycleEpoch != parsed.activeLifecycleEvidence().lifecycleEpoch()
          || catalogRevision != parsed.catalogRevision()
          || !initialAdmissionOwnerProofId.equals(parsed.initialAdmissionOwnerProofId())
          || !initialAdmissionOwnerProofDigest.equals(parsed.initialAdmissionOwnerProofDigest())
          || !pointerAuditId.equals(parsed.pointerAuditId())
          || pointerVersion != parsed.pointerVersion()) {
        throw new OperationBindingConflictException(
            "World initial-location operation id is bound to different immutable source or target selectors");
      }
    }
  }

  /**
   * Stored result bytes and exact room mapping; UNREGISTERED carries no owner or admission claim.
   */
  public record Result(
      Outcome outcome,
      String conflictCode,
      RoomTemplateRef startLocation,
      Long runtimeRoomInstanceId,
      byte[] requestBytes,
      String requestDigest,
      byte[] resultBytes,
      String resultDigest,
      boolean retained) {
    public Result {
      Objects.requireNonNull(outcome, "outcome");
      requestBytes = requestBytes == null ? null : requestBytes.clone();
      resultBytes = resultBytes == null ? null : resultBytes.clone();
    }

    private static Result unregistered() {
      return new Result(Outcome.UNREGISTERED, null, null, null, null, null, null, null, false);
    }

    @Override
    public byte[] requestBytes() {
      return requestBytes == null ? null : requestBytes.clone();
    }

    @Override
    public byte[] resultBytes() {
      return resultBytes == null ? null : resultBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
      return this == other
          || (other instanceof Result that
              && outcome == that.outcome
              && Objects.equals(conflictCode, that.conflictCode)
              && Objects.equals(startLocation, that.startLocation)
              && Objects.equals(runtimeRoomInstanceId, that.runtimeRoomInstanceId)
              && Arrays.equals(requestBytes, that.requestBytes)
              && Objects.equals(requestDigest, that.requestDigest)
              && Arrays.equals(resultBytes, that.resultBytes)
              && Objects.equals(resultDigest, that.resultDigest)
              && retained == that.retained);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(outcome, conflictCode, startLocation, runtimeRoomInstanceId);
      result = 31 * result + Arrays.hashCode(requestBytes);
      result = 31 * result + Objects.hashCode(requestDigest);
      result = 31 * result + Arrays.hashCode(resultBytes);
      result = 31 * result + Objects.hash(resultDigest, retained);
      return result;
    }
  }

  public static final class OperationBindingConflictException extends IllegalStateException {
    public OperationBindingConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidInitialLocationEvidenceException
      extends IllegalArgumentException {
    public InvalidInitialLocationEvidenceException(String message) {
      super(message);
    }

    public InvalidInitialLocationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class InvalidInitialLocationReadbackException extends IllegalStateException {
    public InvalidInitialLocationReadbackException(String message) {
      super(message);
    }

    public InvalidInitialLocationReadbackException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private record ParsedRequest(
      Request request,
      UUID operationId,
      UUID canonicalTenantId,
      UUID playableStateNamespaceId,
      UUID canonicalGameInstanceId,
      UUID canonicalAccountId,
      UUID characterId,
      UUID entityAssignmentOperationId,
      String entityAssignmentDigest,
      UUID realmId,
      String worldSlug,
      String playableStateScope,
      UUID canonicalVersionId,
      UUID initialAdmissionHoldId,
      UUID initialAdmissionHoldFence,
      String initialAdmissionRequestId,
      String initialAdmissionRequestDigest,
      long catalogRevision,
      String initialAdmissionOwnerProofId,
      String initialAdmissionOwnerProofDigest,
      String pointerAuditId,
      long pointerVersion,
      WorldCanonicalInstanceLifecycleEvidence activeLifecycleEvidence,
      PlayerCreatedActorOrigin entityCreatedOrigin) {}
}
