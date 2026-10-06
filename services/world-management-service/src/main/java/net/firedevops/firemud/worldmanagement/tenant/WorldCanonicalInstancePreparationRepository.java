package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Result;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.Entry;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * World-owned storage for generation-free canonical instance materialization.
 *
 * <p>This repository is deliberately not registered as a Spring component. The only write path is
 * the exact V35 database function, followed in the same transaction by V34 association retention.
 * It reads the immutable V31 capture independently before writing and reconstructs the exact result
 * only after commit.
 */
public final class WorldCanonicalInstancePreparationRepository {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final DSLContext dsl;
  private final TransactionTemplate transaction;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;
  private final WorldCanonicalFrozenTopologyRepository frozenTopologyRepository;
  private final WorldAuthoredSourceIntakeRepository sourceIntakeRepository;
  private final WorldCompleteLaunchBindingRepository launchBindingRepository;
  private final WorldAuthoredVersionIdentityRepository versionIdentityRepository;

  public WorldCanonicalInstancePreparationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associationRepository,
      WorldCanonicalFrozenTopologyRepository frozenTopologyRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    this.frozenTopologyRepository =
        Objects.requireNonNull(frozenTopologyRepository, "frozenTopologyRepository");
    sourceIntakeRepository = new WorldAuthoredSourceIntakeRepository(dsl);
    launchBindingRepository = new WorldCompleteLaunchBindingRepository(dsl);
    versionIdentityRepository = new WorldAuthoredVersionIdentityRepository(dsl);
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Runs the one owner transaction, then independently reads its committed immutable result. The
   * supplied continuously held authority handle remains valid through the transaction manager's
   * commit path.
   */
  public Result materialize(
      Input input, WorldCanonicalInstancePreparationService.HeldCommitAuthority authority) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(authority, "authority");
    requireNoActiveTransaction("Canonical World preparation");
    requireExactOriginalBindings(input);
    requireExactFrozenSource(input);
    String inputJson = inputJson(input);
    String inputDigest = digest(inputJson.getBytes(StandardCharsets.UTF_8));

    MaterializedInstance committed =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  requireWritableReadCommittedTransaction();
                  authority.requireHeld();
                  Record row =
                      Objects.requireNonNull(
                          fetchCanonicalPreparation(inputJson, inputDigest),
                          "V35 canonical preparation function returned no row");
                  MaterializedInstance materialized =
                      new MaterializedInstance(
                          required(row, "world_instance_id", Long.class),
                          required(row, "private_game_instance_key", Long.class));
                  WorldCanonicalInstanceAssociation.Claim claim =
                      new WorldCanonicalInstanceAssociation.Claim(
                          input.gameSessionReadRequest(),
                          input.gameSessionReadEvidence(),
                          materialized.worldInstanceId(),
                          input.completeLaunchBinding(),
                          input.versionIdentity());
                  associationRepository.retainClaimInOwnerTransaction(claim);
                  authority.requireHeld();
                  return materialized;
                }),
            "Canonical World preparation transaction did not commit");

    // The authority handle is still open here: TransactionTemplate returned only after commit.
    authority.requireHeld();
    Result result =
        readOwnerPreparation(input)
            .orElseThrow(
                () ->
                    new InvalidPreparationEvidenceException(
                        "Committed canonical World preparation has no independent result readback"));
    if (result.association().worldInstanceId() != committed.worldInstanceId()) {
      throw new InvalidPreparationEvidenceException(
          "Canonical World preparation readback differs from its committed owner row");
    }
    return result;
  }

  /**
   * Calls the V35 writer inside its owner transaction and translates only its explicit
   * canonical-identity conflict signals. Throwing the domain exception here keeps the transaction
   * rollback behavior while leaving unrelated database uniqueness failures visible as such.
   */
  private Record fetchCanonicalPreparation(String inputJson, String inputDigest) {
    try {
      return dsl.fetchOne(
          "SELECT * FROM world_prepare_canonical_instance(?, ?)", inputJson, inputDigest);
    } catch (DuplicateKeyException exception) {
      throw translateCanonicalPreparationConflict(exception);
    }
  }

  static RuntimeException translateCanonicalPreparationConflict(DuplicateKeyException exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && "23505".equals(sqlException.getSQLState())
          && isCanonicalPreparationConflictMessage(sqlException.getMessage())) {
        return new ConflictingPreparationException(
            "Canonical gameInstanceId is already bound to a different preparation", exception);
      }
    }
    return exception;
  }

  private static boolean isCanonicalPreparationConflictMessage(String message) {
    if (message == null) return false;
    String ownerMessage = message.lines().findFirst().orElse("");
    if (ownerMessage.startsWith("ERROR: ")) ownerMessage = ownerMessage.substring(7);
    return ownerMessage.equals(
            "Canonical gameInstanceId is already bound to a different preparation")
        || ownerMessage.equals("Canonical game instance or request identity is already reserved");
  }

  /** Independent exact read; lifecycle state is intentionally absent from the retained result. */
  public Optional<Result> readOwnerPreparation(Input input) {
    Objects.requireNonNull(input, "input");
    requireNoActiveTransaction("Canonical World preparation readback");
    WorldCanonicalInstancePreparation.requireExactReleaseGraph(
        input.completeLaunchBinding().evidence().releaseAttestation(), input.topologyPlan());
    String inputJson = inputJson(input);
    String inputDigest = digest(inputJson.getBytes(StandardCharsets.UTF_8));
    Record row =
        dsl.fetchOne(
            "SELECT canonical_game_instance_id, capture_id, graph_sha256, input_digest, input_json, "
                + "graph_bytes, world_instance_id, private_game_instance_key, region_count, zone_count, "
                + "room_count, exit_count, storage_status "
                + "FROM world_canonical_instance_preparation WHERE canonical_game_instance_id = ?",
            input.canonicalGameInstanceId());
    if (row == null) return Optional.empty();

    if (!inputDigest.equals(required(row, "input_digest", String.class))
        || !inputJson.equals(required(row, "input_json", String.class))
        || !input.captureId().equals(required(row, "capture_id", java.util.UUID.class))) {
      throw new ConflictingPreparationException(
          "Canonical gameInstanceId is already bound to a different complete preparation input");
    }
    byte[] graphBytes = required(row, "graph_bytes", byte[].class);
    String graphSha256 = required(row, "graph_sha256", String.class);
    if (!HexFormat.of().formatHex(sha256(graphBytes)).equals(graphSha256)) {
      throw new InvalidPreparationEvidenceException(
          "Retained canonical preparation graph bytes differ from their immutable digest");
    }

    WorldCanonicalInstanceAssociation association =
        associationRepository
            .readOwnerAssociation(input.canonicalGameInstanceId())
            .orElseThrow(
                () ->
                    new InvalidPreparationEvidenceException(
                        "Canonical World preparation result lost its exact V34 association"));
    if (!association.identity().equals(input.gameSessionReadEvidence().canonicalIdentity())
        || !association.completeLaunchBinding().equals(input.completeLaunchBinding())
        || !association.versionIdentity().equals(input.versionIdentity())
        || association.worldInstanceId() != required(row, "world_instance_id", Long.class)) {
      throw new InvalidPreparationEvidenceException(
          "Canonical World preparation association differs from its complete immutable input");
    }

    int regions = required(row, "region_count", Integer.class);
    int zones = required(row, "zone_count", Integer.class);
    int rooms = required(row, "room_count", Integer.class);
    int exits = required(row, "exit_count", Integer.class);
    if (regions != input.topologyPlan().regions().size()
        || zones != input.topologyPlan().zones().size()
        || rooms != input.topologyPlan().rooms().size()
        || exits != input.topologyPlan().roomExits().size()) {
      throw new InvalidPreparationEvidenceException(
          "Canonical World preparation readback omitted or added a selected topology family row");
    }
    var selector =
        input.completeLaunchBinding().evidence().releaseAttestation().worldStartLocationEvidence();
    RoomTemplateRef startLocation = null;
    Long runtimeRoomInstanceId = null;
    if (selector != null) {
      requireExactOriginalSelector(input, graphBytes);
      var receipt = WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
      Record selected =
          dsl.fetchOne(
              "SELECT s.*, m.runtime_room_instance_id AS mapped_room_instance_id, "
                  + "r.room_instance_row_id AS actual_room_instance_id,r.tenant_id AS room_tenant_key,r.game_instance_id AS room_game_instance_key "
                  + "FROM world_canonical_preparation_start_location s "
                  + "JOIN world_canonical_instance_topology_identity m ON m.world_instance_id=s.world_instance_id "
                  + "AND m.canonical_game_instance_id=s.canonical_game_instance_id AND m.family='ROOM' "
                  + "AND m.template_id=s.room_template_id JOIN room_instance r ON r.id=m.runtime_row_id "
                  + "WHERE s.canonical_game_instance_id=?",
              input.canonicalGameInstanceId());
      if (selected == null
          || !Arrays.equals(
              selector.canonicalBytes(), required(selected, "evidence_bytes", byte[].class))
          || !receipt
              .startLocation()
              .tenantId()
              .equals(required(selected, "canonical_tenant_id", java.util.UUID.class))
          || !receipt
              .startLocation()
              .versionId()
              .equals(required(selected, "canonical_version_id", java.util.UUID.class))
          || !receipt
              .startLocation()
              .roomTemplateId()
              .equals(required(selected, "room_template_id", java.util.UUID.class))
          || !receipt.receiptDigest().equals(required(selected, "receipt_digest", String.class))
          || !receipt.graphDigest().equals(required(selected, "graph_digest", String.class))
          || !Objects.equals(
              required(selected, "runtime_room_instance_id", Long.class),
              required(selected, "mapped_room_instance_id", Long.class))
          || !Objects.equals(
              required(selected, "runtime_room_instance_id", Long.class),
              required(selected, "actual_room_instance_id", Long.class))
          || required(selected, "room_tenant_key", Long.class)
              != input.versionIdentity().sourceIntakeReceipt().localTenantKey()
          || !Objects.equals(
              required(selected, "room_game_instance_key", Long.class),
              required(row, "private_game_instance_key", Long.class))
          || required(selected, "world_instance_id", Long.class) != association.worldInstanceId()) {
        throw new InvalidPreparationEvidenceException(
            "Canonical preparation lost exact original selector or runtime ROOM mapping");
      }
      startLocation = receipt.startLocation();
      runtimeRoomInstanceId = required(selected, "runtime_room_instance_id", Long.class);
    }
    return Optional.of(
        new Result(
            association,
            input.captureId(),
            graphSha256,
            inputDigest,
            regions,
            zones,
            rooms,
            exits,
            required(row, "storage_status", String.class),
            startLocation,
            runtimeRoomInstanceId));
  }

  private void requireExactFrozenSource(Input input) {
    WorldCanonicalFrozenTopology frozen =
        frozenTopologyRepository
            .readCommitted(input.topologyPlan().sourceBinding())
            .orElseThrow(
                () ->
                    new InvalidPreparationEvidenceException(
                        "Canonical preparation input has no exact committed frozen topology"));
    WorldCanonicalInstanceTopologyPlan reconstructed =
        WorldCanonicalInstanceTopologyPlan.create(frozen);
    if (!frozen.captureId().equals(input.captureId())
        || !matchesCompleteFrozenSource(frozen.request(), input.topologyPlan().sourceBinding())
        || !reconstructed.entries().equals(input.topologyPlan().entries())
        || !reconstructed.regions().equals(input.topologyPlan().regions())
        || !reconstructed.zones().equals(input.topologyPlan().zones())
        || !reconstructed.rooms().equals(input.topologyPlan().rooms())
        || !reconstructed.roomExits().equals(input.topologyPlan().roomExits())
        || !reconstructed.generationRules().equals(input.topologyPlan().generationRules())
        || !reconstructed.spawnBindings().equals(input.topologyPlan().spawnBindings())) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation plan differs from the full immutable capture row/payload/revision/source vector");
    }
    WorldCanonicalInstancePreparation.requireGenerationFree(reconstructed);
    WorldCanonicalInstancePreparation.requireExactReleaseGraph(
        input.completeLaunchBinding().evidence().releaseAttestation(), reconstructed);
    requireExactOriginalSelector(input, frozen.graphBytes());
  }

  /** Exact immutable Account/APPLIED/receipt readback; no mutable remote owner read. */
  private void requireExactOriginalSelector(Input input, byte[] graphBytes) {
    var evidence =
        input.completeLaunchBinding().evidence().releaseAttestation().worldStartLocationEvidence();
    if (evidence == null) return;
    var receipt = WorldDraftStartLocationEvidence.fromStored(evidence.selectorReceiptBytes());
    Record original =
        dsl.fetchOne(
            "SELECT s.receipt_bytes,s.account_binding_bytes,s.graph_digest,a.result_bytes,g.graph_bytes "
                + "FROM world_draft_start_location_receipt s JOIN world_draft_graph_application a "
                + "ON a.operation_id=s.operation_id AND a.request_id=s.request_id AND a.commit_id=s.commit_id "
                + "AND a.authorization_fence_id=s.authorization_fence_id "
                + "JOIN world_topology_draft_commit g ON g.request_id=s.request_id AND g.commit_id=s.commit_id "
                + "WHERE s.operation_id=? AND s.target_namespace=? AND s.canonical_tenant_id=? "
                + "AND s.canonical_version_id=? AND s.room_template_id=?",
            receipt.operationId(),
            receipt.targetNamespace(),
            receipt.startLocation().tenantId(),
            receipt.startLocation().versionId(),
            receipt.startLocation().roomTemplateId());
    if (original == null
        || !Arrays.equals(
            evidence.selectorReceiptBytes(), required(original, "receipt_bytes", byte[].class))
        || !Arrays.equals(
            evidence.originalAccountBindingBytes(),
            required(original, "account_binding_bytes", byte[].class))
        || !Arrays.equals(
            evidence.appliedResultBytes(), required(original, "result_bytes", byte[].class))
        || !Arrays.equals(graphBytes, required(original, "graph_bytes", byte[].class))
        || !receipt.graphDigest().equals(digest(graphBytes))
        || !receipt.graphDigest().equals(required(original, "graph_digest", String.class))) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation selector differs from original World Account/APPLIED/graph receipt");
    }
  }

  /**
   * Compares every immutable value in a frozen request without relying on the enclosing plan's
   * object identity. The plan intentionally has no value equality of its own.
   */
  static boolean matchesCompleteFrozenSource(Request retained, Request supplied) {
    Objects.requireNonNull(retained, "retained");
    Objects.requireNonNull(supplied, "supplied");
    var retainedPlan = retained.plan();
    var suppliedPlan = supplied.plan();
    var retainedGraph = retainedPlan.graph();
    var suppliedGraph = suppliedPlan.graph();
    return retained.freeze().equals(supplied.freeze())
        && retainedPlan.binding().equals(suppliedPlan.binding())
        && retainedPlan.ownerBinding().equals(suppliedPlan.ownerBinding())
        && retainedGraph.tenantId().equals(suppliedGraph.tenantId())
        && retainedGraph.versionId().equals(suppliedGraph.versionId())
        && retainedGraph.freshGraphDeclaration().equals(suppliedGraph.freshGraphDeclaration())
        && retainedGraph.nodes().equals(suppliedGraph.nodes());
  }

  /** Reloads original World-owned source, launch-pair, and Version rows before allocation. */
  private void requireExactOriginalBindings(Input input) {
    var launch = input.completeLaunchBinding();
    var source = launch.sourceIntakeReceipt();
    var retainedSource =
        sourceIntakeRepository
            .read(source.targetNamespace(), source.intakeRequestId())
            .orElseThrow(
                () ->
                    new InvalidPreparationEvidenceException(
                        "Canonical preparation has no exact committed World source intake"));
    if (!retainedSource.equals(source)) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation source differs from its complete original immutable intake row");
    }

    var retainedBinding =
        launchBindingRepository
            .read(
                launch.targetNamespace(),
                launch.canonicalTenantId(),
                launch.controlPlaneRequestId())
            .orElseThrow(
                () ->
                    new InvalidPreparationEvidenceException(
                        "Canonical preparation has no exact committed V26 launch binding"));
    if (!retainedBinding.operationId().equals(launch.operationId())
        || !retainedBinding.targetNamespace().equals(launch.targetNamespace())
        || !retainedBinding.canonicalTenantId().equals(launch.canonicalTenantId())
        || !retainedBinding.worldSlug().equals(launch.worldSlug())
        || !retainedBinding.controlPlaneRequestId().equals(launch.controlPlaneRequestId())
        || !retainedBinding.intakeOperationId().equals(source.operationId())
        || !retainedBinding.intakeRequestId().equals(source.intakeRequestId())
        || retainedBinding.localTenantKey() != source.localTenantKey()
        || !retainedBinding.sourceOperationId().equals(source.sourceOperationId())
        || !retainedBinding.sourceEvidenceDigest().equals(source.sourceEvidenceDigest())
        || !retainedBinding.intakeReceiptDigest().equals(source.receiptDigest())
        || !retainedBinding.descriptorRequestDigest().equals(launch.descriptor().requestDigest())
        || !retainedBinding.descriptorResultDigest().equals(launch.descriptor().resultDigest())
        || !retainedBinding
            .releaseAttestationDigest()
            .equals(launch.evidence().releaseAttestation().evidenceDigest())
        || !retainedBinding.evidence().equals(launch.evidence())) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation V26 launch/source bytes differ from the original committed binding");
    }

    var version = input.versionIdentity();
    var retainedVersion =
        versionIdentityRepository
            .readByCanonicalVersion(
                version.targetNamespace(),
                version.canonicalTenantId(),
                version.worldSlug(),
                version.canonicalVersionId())
            .orElseThrow(
                () ->
                    new InvalidPreparationEvidenceException(
                        "Canonical preparation has no exact committed V27 Version identity"));
    if (!retainedVersion.equals(version) || !retainedVersion.sourceIntakeReceipt().equals(source)) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation V27 Version/source evidence differs from its original immutable row");
    }
  }

  static String inputJson(Input input) {
    var request = input.gameSessionReadRequest();
    var evidence = input.gameSessionReadEvidence();
    var launch = input.completeLaunchBinding();
    var source = launch.sourceIntakeReceipt();
    var version = input.versionIdentity();
    var versionState = version.versionStateEvidence();
    var topology = input.topologyPlan();
    var topologySource = topology.sourceBinding();
    var owner = topologySource.plan().ownerBinding();
    var freeze = topologySource.freeze();

    Map<String, Object> root =
        object(
            "schemaVersion",
                launch.evidence().releaseAttestation().worldStartLocationEvidence() == null ? 1 : 2,
            "identity",
                object(
                    "canonicalGameInstanceId", evidence.canonicalGameInstanceId().toString(),
                    "targetNamespace", evidence.targetNamespace(),
                    "canonicalTenantId", evidence.canonicalTenantId().toString(),
                    "worldSlug", evidence.worldSlug(),
                    "playableStateNamespaceId", evidence.playableStateNamespaceId().toString(),
                    "playableStateScope", evidence.playableStateScope(),
                    "publicProduction", evidence.publicProduction(),
                    "controlPlaneRequestId", evidence.controlPlaneRequestId()),
            "gameSessionReadRequest",
                object(
                    "targetNamespace", request.targetNamespace(),
                    "canonicalTenantId", request.canonicalTenantId().toString(),
                    "worldSlug", request.worldSlug(),
                    "canonicalGameInstanceId", request.canonicalGameInstanceId().toString(),
                    "controlPlaneRequestId", request.controlPlaneRequestId(),
                    "launchDescriptorId", request.launchDescriptorId(),
                    "expectedDescriptorRequestDigest", request.expectedDescriptorRequestDigest(),
                    "expectedDescriptorResultDigest", request.expectedDescriptorResultDigest(),
                    "expectedReleaseAttestationEvidenceDigest",
                        request.expectedReleaseAttestationEvidenceDigest()),
            "gameSessionReadEvidence",
                object(
                    "targetNamespace", evidence.targetNamespace(),
                    "canonicalTenantId", evidence.canonicalTenantId().toString(),
                    "worldSlug", evidence.worldSlug(),
                    "canonicalGameInstanceId", evidence.canonicalGameInstanceId().toString(),
                    "controlPlaneRequestId", evidence.controlPlaneRequestId(),
                    "launchDescriptorId", evidence.launchDescriptorId(),
                    "descriptorRequestDigest", evidence.descriptorRequestDigest(),
                    "descriptorResultDigest", evidence.descriptorResultDigest(),
                    "releaseAttestationEvidenceDigest", evidence.releaseAttestationEvidenceDigest(),
                    "playableStateNamespaceId", evidence.playableStateNamespaceId().toString(),
                    "playableStateScope", evidence.playableStateScope(),
                    "publicProduction", evidence.publicProduction(),
                    "descriptorJson", evidenceJson(evidence.descriptor()),
                    "releaseAttestationJson", evidenceJson(evidence.releaseAttestation())),
            "launchBinding",
                object(
                    "schemaVersion", launch.schemaVersion(),
                    "operationId", launch.operationId().toString(),
                    "targetNamespace", launch.targetNamespace(),
                    "canonicalTenantId", launch.canonicalTenantId().toString(),
                    "worldSlug", launch.worldSlug(),
                    "controlPlaneRequestId", launch.controlPlaneRequestId(),
                    "descriptorRequestDigest", launch.descriptor().requestDigest(),
                    "descriptorResultDigest", launch.descriptor().resultDigest(),
                    "releaseAttestationDigest",
                        launch.evidence().releaseAttestation().evidenceDigest(),
                    "canonicalVersionId",
                        launch.evidence().releaseAttestation().canonicalVersionId().toString(),
                    "localTenantKey", source.localTenantKey(),
                    "intakeOperationId", source.operationId().toString(),
                    "intakeRequestId", source.intakeRequestId().toString(),
                    "sourceOperationId", source.sourceOperationId().toString(),
                    "sourceEvidenceDigest", source.sourceEvidenceDigest(),
                    "intakeRequestDigest", source.requestDigest(),
                    "intakeReceiptDigest", source.receiptDigest()),
            "versionIdentity",
                object(
                    "schemaVersion", version.schemaVersion(),
                    "operationId", version.operationId().toString(),
                    "canonicalVersionId", version.canonicalVersionId().toString(),
                    "localVersionKey", version.localVersionKey(),
                    "gameDesignVersionId", version.gameDesignVersionId(),
                    "versionState", versionState.versionState().name(),
                    "versionStateEpoch", versionState.versionStateEpoch(),
                    "evidenceDigest", versionState.evidenceDigest()),
            "sourceIntake",
                object(
                    "schemaVersion", source.schemaVersion(),
                    "targetNamespace", source.targetNamespace(),
                    "intakeRequestId", source.intakeRequestId().toString(),
                    "operationId", source.operationId().toString(),
                    "canonicalTenantId", source.canonicalTenantId().toString(),
                    "worldSlug", source.worldSlug(),
                    "sourceOperationId", source.sourceOperationId().toString(),
                    "sourceEvidenceDigest", source.sourceEvidenceDigest(),
                    "requestDigest", source.requestDigest(),
                    "receiptDigest", source.receiptDigest(),
                    "localTenantKey", source.localTenantKey(),
                    "sourceEvidence",
                        object(
                            "schemaVersion", source.source().schemaVersion(),
                            "registrationRequestId",
                                source.source().registrationRequestId().toString(),
                            "sourceOperationId", source.source().operationId().toString(),
                            "requestDigest", source.source().requestDigest(),
                            "canonicalTenantId", source.source().canonicalTenantId().toString(),
                            "tenantSlug", source.source().tenantSlug(),
                            "worldSlug", source.source().worldSlug(),
                            "worldDisplayName", source.source().worldDisplayName(),
                            "sourceGameRowId", source.source().sourceGameRowId(),
                            "sourceGameTenantKey", source.source().sourceGameTenantKey(),
                            "provenanceKind", source.source().provenanceKind(),
                            "evidenceDigest", source.source().evidenceDigest())),
            "topology",
                object(
                    "captureId", topology.captureId().toString(),
                    "targetNamespace", owner.targetNamespace(),
                    "canonicalTenantId", owner.canonicalTenantId().toString(),
                    "canonicalVersionId", owner.canonicalVersionId().toString(),
                    "versionIdentityOperationId", owner.versionIdentityOperationId().toString(),
                    "requestId", topologySource.plan().binding().requestId().toString(),
                    "commitId", topologySource.plan().binding().commitId().toString(),
                    "freezeRequestId", freeze.publicationRequestId(),
                    "publicationFence", freeze.publicationFence().toString(),
                    "publicationRequestDigest", freeze.requestDigest(),
                    "appliedCommitId", freeze.appliedCommitId(),
                    "planDigest", planDigest(topology.entries()),
                    "regionCount", topology.regions().size(),
                    "zoneCount", topology.zones().size(),
                    "roomCount", topology.rooms().size(),
                    "exitCount", topology.roomExits().size(),
                    "generationRuleCount", topology.generationRules().size(),
                    "spawnBindingCount", topology.spawnBindings().size()));
    var selector = launch.evidence().releaseAttestation().worldStartLocationEvidence();
    if (selector != null) {
      root.put(
          "worldStartLocationEvidenceBase64",
          Base64.getEncoder().encodeToString(selector.canonicalBytes()));
    }
    try {
      return JSON.writeValueAsString(root);
    } catch (JacksonException exception) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation input cannot be serialized exactly", exception);
    }
  }

  private static String planDigest(List<Entry> entries) {
    List<Map<String, Object>> rows = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      rows.add(
          object(
              "family", entry.identity().family().name(),
              "templateId", entry.identity().templateId().toString(),
              "inputPayload", entry.source().inputPayload(),
              "authoredRevisionBase64",
                  Base64.getEncoder()
                      .encodeToString(entry.source().authoredRevision().toByteArray()),
              "effectiveContentBase64",
                  Base64.getEncoder().encodeToString(entry.source().content().toByteArray())));
    }
    try {
      return digest(JSON.writeValueAsBytes(rows));
    } catch (JacksonException exception) {
      throw new InvalidPreparationEvidenceException(
          "Canonical topology source rows cannot be serialized exactly", exception);
    }
  }

  private static Map<String, Object> object(Object... values) {
    if (values.length % 2 != 0) throw new IllegalArgumentException("Map requires key/value pairs");
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }

  /** Full typed evidence serialization, matching the closed World V26 persistence codec. */
  private static String evidenceJson(Object evidence) {
    try {
      return JSON.writeValueAsString(Objects.requireNonNull(evidence, "evidence"));
    } catch (JacksonException exception) {
      throw new InvalidPreparationEvidenceException(
          "Canonical preparation launch evidence cannot be serialized exactly", exception);
    }
  }

  private static String digest(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(sha256(bytes));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Canonical World preparation requires its owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"read committed".equals(state.get("isolation", String.class))
        || !"off".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "Canonical World preparation requires writable READ COMMITTED isolation");
    }
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " must not join an ambient transaction");
    }
  }

  private static <T> T required(Record row, String name, Class<T> type) {
    T value = row.get(name, type);
    if (value == null) throw new InvalidPreparationEvidenceException("Missing " + name);
    return value;
  }

  private record MaterializedInstance(long worldInstanceId, long privateGameInstanceKey) {}

  public static class ConflictingPreparationException extends IllegalStateException {
    public ConflictingPreparationException(String message) {
      super(message);
    }

    public ConflictingPreparationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class InvalidPreparationEvidenceException extends IllegalStateException {
    public InvalidPreparationEvidenceException(String message) {
      super(message);
    }

    public InvalidPreparationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
