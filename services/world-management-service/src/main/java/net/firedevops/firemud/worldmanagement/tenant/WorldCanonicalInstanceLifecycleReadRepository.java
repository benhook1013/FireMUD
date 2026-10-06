package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reconstructs a current canonical World lifecycle snapshot from one owner-local MVCC snapshot.
 *
 * <p>This repository is intentionally not registered as a Spring component. A positive result is
 * exact World-owned evidence, not current Account authority, an activation command, or gameplay
 * admission permission.
 */
public final class WorldCanonicalInstanceLifecycleReadRepository {
  private static final Set<String> INPUT_FIELDS =
      Set.of(
          "schemaVersion",
          "identity",
          "gameSessionReadRequest",
          "gameSessionReadEvidence",
          "launchBinding",
          "versionIdentity",
          "sourceIntake",
          "topology",
          "worldStartLocationEvidenceBase64");
  private static final JsonMapper CLOSED_JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private final DSLContext dsl;
  private final TransactionTemplate transaction;
  private final WorldCanonicalInstanceAssociationRepository associationRepository;

  public WorldCanonicalInstanceLifecycleReadRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceAssociationRepository associationRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.associationRepository =
        Objects.requireNonNull(associationRepository, "associationRepository");
    transaction =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setReadOnly(true);
  }

  /**
   * Reads only the exact canonical association requested, then follows its private World row key.
   * Missing association or not-yet-materialized preparation is an ordinary empty read.
   */
  public Optional<WorldCanonicalInstanceLifecycleEvidence> read(
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction("World canonical lifecycle read");
    return Optional.ofNullable(
        transaction.execute(
            status -> {
              requireReadOnlyRepeatableReadOwnerTransaction();
              try {
                Optional<WorldCanonicalInstanceAssociation> maybeAssociation =
                    associationRepository.readOwnerAssociationInOwnerTransaction(
                        request.canonicalGameInstanceId());
                if (maybeAssociation.isEmpty()) return null;
                WorldCanonicalInstanceAssociation association = maybeAssociation.orElseThrow();
                if (!matchesRequest(request, association)) return null;

                Record preparation =
                    dsl.fetchOne(
                        "SELECT canonical_game_instance_id, capture_id, graph_sha256, input_digest, input_json, "
                            + "graph_bytes, world_instance_id, private_game_instance_key, region_count, zone_count, "
                            + "room_count, exit_count, storage_status "
                            + "FROM world_canonical_instance_preparation WHERE canonical_game_instance_id = ?",
                        request.canonicalGameInstanceId());
                if (preparation == null) return null;
                return readMaterialized(request, association, preparation);
              } catch (TransientDataAccessException | DataAccessException unavailable) {
                throw unavailable;
              } catch (
                  WorldCanonicalInstanceAssociationRepository.InvalidAssociationEvidenceException
                      inconsistent) {
                throw new InvalidLifecycleEvidenceException(
                    "Canonical World association source evidence is inconsistent", inconsistent);
              }
            }));
  }

  private WorldCanonicalInstanceLifecycleEvidence readMaterialized(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      WorldCanonicalInstanceAssociation association,
      Record preparation) {
    try {
      UUID canonicalGameInstanceId = request.canonicalGameInstanceId();
      UUID captureId = required(preparation, "capture_id", UUID.class);
      String graphSha256 = required(preparation, "graph_sha256", String.class);
      String inputDigest = required(preparation, "input_digest", String.class);
      String inputJson = required(preparation, "input_json", String.class);
      byte[] graphBytes = required(preparation, "graph_bytes", byte[].class);
      if (!canonicalGameInstanceId.equals(
              required(preparation, "canonical_game_instance_id", UUID.class))
          || required(preparation, "world_instance_id", Long.class) != association.worldInstanceId()
          || required(preparation, "private_game_instance_key", Long.class)
              != association.worldPrepareFields().privateGameInstanceKey()
          || !"MATERIALIZED_UNVERIFIED"
              .equals(required(preparation, "storage_status", String.class))) {
        throw invalid(
            "Retained World preparation differs from its immutable canonical association");
      }
      String retainedGraphSha = HexFormat.of().formatHex(sha256(graphBytes));
      String retainedInputDigest = digest(inputJson.getBytes(StandardCharsets.UTF_8));
      if (!retainedGraphSha.equals(graphSha256) || !retainedInputDigest.equals(inputDigest)) {
        throw invalid("Retained World preparation bytes differ from their immutable digests");
      }

      JsonNode input = parseInput(inputJson);
      requireInputIdentity(input, request, association, captureId);
      requirePreparationCounts(input, preparation);

      var release = association.completeLaunchBinding().evidence().releaseAttestation();
      if (release.schemaVersion()
              != net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence
                  .SELECTOR_SCHEMA_VERSION
          || release.worldStartLocationEvidence() == null) {
        throw invalid("Receipt-less retained V1 preparation cannot produce lifecycle evidence");
      }
      WorldPublishedStartLocationEvidence selector = release.worldStartLocationEvidence();
      byte[] selectorBytes = selector.canonicalBytes();
      if (!input.has("worldStartLocationEvidenceBase64")
          || !java.util.Base64.getEncoder()
              .encodeToString(selectorBytes)
              .equals(input.get("worldStartLocationEvidenceBase64").textValue())) {
        throw invalid(
            "Retained V2 preparation selector differs from its complete release evidence");
      }
      WorldDraftStartLocationEvidence receipt =
          WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
      RoomTemplateRef startLocation = receipt.startLocation();
      if (!request.canonicalTenantId().equals(startLocation.tenantId())
          || !request.canonicalVersionId().equals(startLocation.versionId())) {
        throw invalid("Original World ROOM selector differs from the requested canonical Version");
      }
      requireOriginalSelector(selector, receipt, graphBytes);
      RoomMapping mapping = requireMappedRoom(association, startLocation, selectorBytes);
      Record lifecycle = readLifecycleRow(association, request);

      return new WorldCanonicalInstanceLifecycleEvidence(
          request,
          association.completeLaunchBinding().evidence(),
          startLocation,
          mapping.runtimeRoomInstanceId(),
          required(lifecycle, "status", String.class),
          required(lifecycle, "lifecycle_epoch", Long.class),
          required(lifecycle, "row_version", Long.class),
          captureId,
          graphSha256,
          inputDigest);
    } catch (InvalidLifecycleEvidenceException invalid) {
      throw invalid;
    } catch (TransientDataAccessException | DataAccessException unavailable) {
      throw unavailable;
    } catch (RuntimeException invalid) {
      throw new InvalidLifecycleEvidenceException(
          "Retained canonical World lifecycle source evidence is inconsistent", invalid);
    }
  }

  private void requireInputIdentity(
      JsonNode input,
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      WorldCanonicalInstanceAssociation association,
      UUID captureId) {
    JsonNode identity = requiredObject(input, "identity");
    requireText(identity, "canonicalGameInstanceId", request.canonicalGameInstanceId().toString());
    requireText(identity, "targetNamespace", request.targetNamespace());
    requireText(identity, "canonicalTenantId", request.canonicalTenantId().toString());
    requireText(identity, "worldSlug", request.worldSlug());
    requireText(
        identity, "playableStateNamespaceId", request.playableStateNamespaceId().toString());
    requireText(identity, "playableStateScope", request.playableStateScope());
    requireBoolean(identity, "publicProduction", request.publicProduction());
    requireText(identity, "controlPlaneRequestId", request.controlPlaneRequestId());

    JsonNode gsRequest = requiredObject(input, "gameSessionReadRequest");
    requireText(gsRequest, "targetNamespace", request.targetNamespace());
    requireText(gsRequest, "canonicalTenantId", request.canonicalTenantId().toString());
    requireText(gsRequest, "worldSlug", request.worldSlug());
    requireText(gsRequest, "canonicalGameInstanceId", request.canonicalGameInstanceId().toString());
    requireText(gsRequest, "controlPlaneRequestId", request.controlPlaneRequestId());
    requireText(
        gsRequest, "expectedDescriptorRequestDigest", request.expectedDescriptorRequestDigest());
    requireText(
        gsRequest, "expectedDescriptorResultDigest", request.expectedDescriptorResultDigest());
    requireText(
        gsRequest,
        "expectedReleaseAttestationEvidenceDigest",
        request.expectedReleaseAttestationDigest());
    var descriptor = association.completeLaunchBinding().descriptor();
    requireText(gsRequest, "launchDescriptorId", descriptor.launchDescriptorId());

    JsonNode gsEvidence = requiredObject(input, "gameSessionReadEvidence");
    requireText(gsEvidence, "targetNamespace", request.targetNamespace());
    requireText(gsEvidence, "canonicalTenantId", request.canonicalTenantId().toString());
    requireText(gsEvidence, "worldSlug", request.worldSlug());
    requireText(
        gsEvidence, "canonicalGameInstanceId", request.canonicalGameInstanceId().toString());
    requireText(gsEvidence, "controlPlaneRequestId", request.controlPlaneRequestId());
    requireText(
        gsEvidence, "playableStateNamespaceId", request.playableStateNamespaceId().toString());
    requireText(gsEvidence, "playableStateScope", request.playableStateScope());
    requireBoolean(gsEvidence, "publicProduction", request.publicProduction());
    requireText(gsEvidence, "descriptorRequestDigest", request.expectedDescriptorRequestDigest());
    requireText(gsEvidence, "descriptorResultDigest", request.expectedDescriptorResultDigest());
    requireText(
        gsEvidence, "releaseAttestationEvidenceDigest", request.expectedReleaseAttestationDigest());
    requireText(gsEvidence, "launchDescriptorId", descriptor.launchDescriptorId());
    requireText(gsEvidence, "descriptorJson", writeClosedEvidenceJson(descriptor));
    requireText(
        gsEvidence,
        "releaseAttestationJson",
        writeClosedEvidenceJson(
            association.completeLaunchBinding().evidence().releaseAttestation()));

    var binding = association.completeLaunchBinding();
    JsonNode launch = requiredObject(input, "launchBinding");
    requireInteger(launch, "schemaVersion", binding.schemaVersion());
    requireText(launch, "operationId", binding.operationId().toString());
    requireText(launch, "targetNamespace", request.targetNamespace());
    requireText(launch, "canonicalTenantId", request.canonicalTenantId().toString());
    requireText(launch, "worldSlug", request.worldSlug());
    requireText(launch, "controlPlaneRequestId", request.controlPlaneRequestId());
    requireText(launch, "descriptorRequestDigest", request.expectedDescriptorRequestDigest());
    requireText(launch, "descriptorResultDigest", request.expectedDescriptorResultDigest());
    requireText(launch, "releaseAttestationDigest", request.expectedReleaseAttestationDigest());
    requireText(launch, "canonicalVersionId", request.canonicalVersionId().toString());
    requireText(
        launch, "intakeRequestId", binding.sourceIntakeReceipt().intakeRequestId().toString());
    requireInteger(launch, "localTenantKey", binding.sourceIntakeReceipt().localTenantKey());
    requireText(
        launch, "intakeOperationId", binding.sourceIntakeReceipt().operationId().toString());
    requireText(
        launch, "sourceOperationId", binding.sourceIntakeReceipt().sourceOperationId().toString());
    requireText(
        launch, "sourceEvidenceDigest", binding.sourceIntakeReceipt().sourceEvidenceDigest());
    requireText(launch, "intakeRequestDigest", binding.sourceIntakeReceipt().requestDigest());
    requireText(launch, "intakeReceiptDigest", binding.sourceIntakeReceipt().receiptDigest());

    var version = association.versionIdentity();
    JsonNode versionJson = requiredObject(input, "versionIdentity");
    requireInteger(versionJson, "schemaVersion", version.schemaVersion());
    requireText(versionJson, "operationId", version.operationId().toString());
    requireText(versionJson, "canonicalVersionId", request.canonicalVersionId().toString());
    requireInteger(versionJson, "localVersionKey", version.localVersionKey());
    requireInteger(versionJson, "gameDesignVersionId", version.gameDesignVersionId());
    requireInteger(
        versionJson, "versionStateEpoch", version.versionStateEvidence().versionStateEpoch());
    requireText(versionJson, "versionState", version.versionStateEvidence().versionState().name());
    requireText(versionJson, "evidenceDigest", version.versionStateEvidence().evidenceDigest());

    JsonNode source = requiredObject(input, "sourceIntake");
    requireInteger(source, "schemaVersion", binding.sourceIntakeReceipt().schemaVersion());
    requireText(source, "targetNamespace", request.targetNamespace());
    requireText(
        source, "intakeRequestId", binding.sourceIntakeReceipt().intakeRequestId().toString());
    requireText(source, "operationId", binding.sourceIntakeReceipt().operationId().toString());
    requireText(source, "canonicalTenantId", request.canonicalTenantId().toString());
    requireText(source, "worldSlug", request.worldSlug());
    requireText(
        source, "sourceOperationId", binding.sourceIntakeReceipt().sourceOperationId().toString());
    requireText(
        source, "sourceEvidenceDigest", binding.sourceIntakeReceipt().sourceEvidenceDigest());
    requireText(source, "receiptDigest", binding.sourceIntakeReceipt().receiptDigest());
    requireText(source, "requestDigest", binding.sourceIntakeReceipt().requestDigest());
    requireInteger(source, "localTenantKey", binding.sourceIntakeReceipt().localTenantKey());
    requireSourceEvidenceObject(source, binding.sourceIntakeReceipt().source());

    JsonNode topology = requiredObject(input, "topology");
    requireText(topology, "captureId", captureId.toString());
    requireText(topology, "targetNamespace", request.targetNamespace());
    requireText(topology, "canonicalTenantId", request.canonicalTenantId().toString());
    requireText(topology, "canonicalVersionId", request.canonicalVersionId().toString());
    requireText(topology, "versionIdentityOperationId", version.operationId().toString());
  }

  private void requirePreparationCounts(JsonNode input, Record preparation) {
    JsonNode topology = requiredObject(input, "topology");
    requireInteger(topology, "regionCount", required(preparation, "region_count", Integer.class));
    requireInteger(topology, "zoneCount", required(preparation, "zone_count", Integer.class));
    requireInteger(topology, "roomCount", required(preparation, "room_count", Integer.class));
    requireInteger(topology, "exitCount", required(preparation, "exit_count", Integer.class));
    if (required(preparation, "region_count", Integer.class) <= 0
        || required(preparation, "zone_count", Integer.class) < 0
        || required(preparation, "room_count", Integer.class) < 0
        || required(preparation, "exit_count", Integer.class) < 0) {
      throw invalid("Retained World preparation topology counts are invalid");
    }
  }

  private void requireOriginalSelector(
      WorldPublishedStartLocationEvidence selector,
      WorldDraftStartLocationEvidence receipt,
      byte[] graphBytes) {
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
            selector.selectorReceiptBytes(), required(original, "receipt_bytes", byte[].class))
        || !Arrays.equals(
            selector.originalAccountBindingBytes(),
            required(original, "account_binding_bytes", byte[].class))
        || !Arrays.equals(
            selector.appliedResultBytes(), required(original, "result_bytes", byte[].class))
        || !Arrays.equals(graphBytes, required(original, "graph_bytes", byte[].class))
        || !receipt.graphDigest().equals(digest(graphBytes))
        || !receipt.graphDigest().equals(required(original, "graph_digest", String.class))) {
      throw invalid("Canonical preparation selector differs from original World source evidence");
    }
  }

  private RoomMapping requireMappedRoom(
      WorldCanonicalInstanceAssociation association,
      RoomTemplateRef startLocation,
      byte[] selectorBytes) {
    Record selected =
        dsl.fetchOne(
            "SELECT s.world_instance_id,s.canonical_tenant_id,s.canonical_version_id,s.room_template_id, "
                + "s.runtime_room_instance_id,s.receipt_digest,s.graph_digest,s.evidence_bytes, "
                + "m.runtime_room_instance_id AS mapped_room_instance_id,m.runtime_row_id AS mapped_room_row_id, "
                + "m.template_id AS mapped_template_id,r.room_instance_row_id AS actual_room_instance_id, "
                + "r.tenant_id AS room_tenant_key,r.game_instance_id AS room_game_instance_key, "
                + "ri.tenant_id AS region_tenant_key,ri.game_instance_id AS region_game_instance_key, "
                + "ri.world_instance_id AS room_world_instance_id "
                + "FROM world_canonical_preparation_start_location s "
                + "JOIN world_canonical_instance_topology_identity m ON m.world_instance_id=s.world_instance_id "
                + "AND m.canonical_game_instance_id=s.canonical_game_instance_id AND m.family='ROOM' "
                + "AND m.template_id=s.room_template_id "
                + "JOIN room_instance r ON r.id=m.runtime_row_id "
                + "JOIN region_instance ri ON ri.id=r.region_instance_id "
                + "WHERE s.canonical_game_instance_id=?",
            association.identity().canonicalGameInstanceId());
    if (selected == null
        || required(selected, "world_instance_id", Long.class) != association.worldInstanceId()
        || !association
            .identity()
            .canonicalTenantId()
            .equals(required(selected, "canonical_tenant_id", UUID.class))
        || !association
            .canonicalVersionId()
            .equals(required(selected, "canonical_version_id", UUID.class))
        || !startLocation
            .roomTemplateId()
            .equals(required(selected, "room_template_id", UUID.class))
        || required(selected, "runtime_room_instance_id", Long.class)
            != required(selected, "mapped_room_instance_id", Long.class)
        || required(selected, "runtime_room_instance_id", Long.class)
            != required(selected, "mapped_room_row_id", Long.class)
        || required(selected, "runtime_room_instance_id", Long.class)
            != required(selected, "actual_room_instance_id", Long.class)
        || !startLocation
            .roomTemplateId()
            .equals(required(selected, "mapped_template_id", UUID.class))
        || !Arrays.equals(selectorBytes, required(selected, "evidence_bytes", byte[].class))
        || !receiptDigestFromEvidence(selectorBytes)
            .equals(required(selected, "receipt_digest", String.class))
        || !WorldDraftStartLocationEvidence.fromStored(
                WorldPublishedStartLocationEvidence.fromStored(selectorBytes)
                    .selectorReceiptBytes())
            .graphDigest()
            .equals(required(selected, "graph_digest", String.class))
        || required(selected, "room_tenant_key", Long.class)
            != association.worldPrepareFields().privateTenantKey()
        || required(selected, "room_game_instance_key", Long.class)
            != association.worldPrepareFields().privateGameInstanceKey()
        || required(selected, "region_tenant_key", Long.class)
            != association.worldPrepareFields().privateTenantKey()
        || required(selected, "region_game_instance_key", Long.class)
            != association.worldPrepareFields().privateGameInstanceKey()
        || required(selected, "room_world_instance_id", Long.class)
            != association.worldInstanceId()) {
      throw invalid("Canonical ROOM selector is not the exact mapped scoped World runtime room");
    }
    return new RoomMapping(required(selected, "runtime_room_instance_id", Long.class));
  }

  private Record readLifecycleRow(
      WorldCanonicalInstanceAssociation association,
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    Record row =
        dsl.fetchOne("SELECT * FROM world_instance WHERE id=?", association.worldInstanceId());
    if (row == null
        || required(row, "id", Long.class) != association.worldInstanceId()
        || !request
            .canonicalGameInstanceId()
            .equals(required(row, "canonical_game_instance_id", UUID.class))
        || !request
            .targetNamespace()
            .equals(required(row, "canonical_target_namespace", String.class))
        || !request.canonicalTenantId().equals(required(row, "canonical_tenant_id", UUID.class))
        || !request.worldSlug().equals(required(row, "canonical_world_slug", String.class))
        || !request
            .playableStateNamespaceId()
            .equals(required(row, "playable_state_namespace_id", UUID.class))
        || !request.playableStateScope().equals(required(row, "playable_state_scope", String.class))
        || request.publicProduction() != required(row, "public_production", Boolean.class)
        || !request
            .controlPlaneRequestId()
            .equals(required(row, "control_plane_request_id", String.class))
        || !association
            .launchBindingOperationId()
            .equals(required(row, "canonical_launch_binding_operation_id", UUID.class))
        || required(row, "tenant_id", Long.class)
            != association.worldPrepareFields().privateTenantKey()
        || required(row, "game_instance_id", Long.class)
            != association.worldPrepareFields().privateGameInstanceKey()
        || required(row, "version_id", Long.class)
            != association.worldPrepareFields().localVersionKey()) {
      throw invalid(
          "Current World lifecycle row differs from its exact canonical owner association");
    }
    long lifecycleEpoch = required(row, "lifecycle_epoch", Long.class);
    long rowVersion = required(row, "row_version", Long.class);
    if (lifecycleEpoch <= 0 || rowVersion < 0) {
      throw invalid("Current World lifecycle row has an invalid epoch or row version");
    }
    return row;
  }

  private static boolean matchesRequest(
      WorldCanonicalInstanceLifecycleEvidence.Request request,
      WorldCanonicalInstanceAssociation association) {
    var identity = association.identity();
    var binding = association.completeLaunchBinding();
    var release = binding.evidence().releaseAttestation();
    return request.canonicalGameInstanceId().equals(identity.canonicalGameInstanceId())
        && request.targetNamespace().equals(identity.targetNamespace())
        && request.canonicalTenantId().equals(identity.canonicalTenantId())
        && request.worldSlug().equals(identity.worldSlug())
        && request.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        && request.playableStateScope().equals(identity.playableStateScope())
        && request.publicProduction() == identity.publicProduction()
        && request.controlPlaneRequestId().equals(identity.controlPlaneRequestId())
        && request.canonicalVersionId().equals(association.canonicalVersionId())
        && request.expectedDescriptorRequestDigest().equals(binding.descriptor().requestDigest())
        && request.expectedDescriptorResultDigest().equals(binding.descriptor().resultDigest())
        && request.expectedReleaseAttestationDigest().equals(release.evidenceDigest());
  }

  private static JsonNode parseInput(String inputJson) {
    try {
      JsonNode input = CLOSED_JSON.readTree(inputJson);
      requireFields(input, INPUT_FIELDS, "Retained World preparation input");
      if (intValue(input, "schemaVersion") != 2) {
        throw invalid("Receipt-less retained V1 preparation cannot produce lifecycle evidence");
      }
      if (!CLOSED_JSON.writeValueAsString(input).equals(inputJson)) {
        throw invalid("Retained World preparation input is not in its original closed form");
      }
      return input;
    } catch (tools.jackson.core.JacksonException invalidJson) {
      throw new InvalidLifecycleEvidenceException(
          "Retained World preparation input is not closed JSON", invalidJson);
    }
  }

  private static void requireFields(JsonNode value, Set<String> expected, String label) {
    if (value == null || !value.isObject()) throw invalid(label + " must be an object");
    Set<String> actual = new java.util.HashSet<>();
    value.propertyNames().forEach(actual::add);
    if (!actual.equals(expected)) throw invalid(label + " has missing or unsupported fields");
  }

  private static void requireSourceEvidenceObject(
      JsonNode retained, AuthoredWorldSourceEvidence evidence) {
    JsonNode actual = requiredObject(retained, "sourceEvidence");
    Set<String> fields =
        Set.of(
            "schemaVersion",
            "registrationRequestId",
            "sourceOperationId",
            "requestDigest",
            "canonicalTenantId",
            "tenantSlug",
            "worldSlug",
            "worldDisplayName",
            "sourceGameRowId",
            "sourceGameTenantKey",
            "provenanceKind",
            "evidenceDigest");
    requireFields(actual, fields, "Retained World source evidence");
    requireInteger(actual, "schemaVersion", evidence.schemaVersion());
    requireText(actual, "registrationRequestId", evidence.registrationRequestId().toString());
    requireText(actual, "sourceOperationId", evidence.operationId().toString());
    requireText(actual, "requestDigest", evidence.requestDigest());
    requireText(actual, "canonicalTenantId", evidence.canonicalTenantId().toString());
    requireText(actual, "tenantSlug", evidence.tenantSlug());
    requireText(actual, "worldSlug", evidence.worldSlug());
    requireText(actual, "worldDisplayName", evidence.worldDisplayName());
    requireInteger(actual, "sourceGameRowId", evidence.sourceGameRowId());
    requireText(actual, "sourceGameTenantKey", evidence.sourceGameTenantKey());
    requireText(actual, "provenanceKind", evidence.provenanceKind());
    requireText(actual, "evidenceDigest", evidence.evidenceDigest());
  }

  private static JsonNode requiredObject(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isObject()) throw invalid(field + " must be an object");
    return value;
  }

  private static void requireText(JsonNode parent, String field, String expected) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isTextual() || !expected.equals(value.textValue())) {
      throw invalid("Retained World preparation " + field + " differs from its exact owner input");
    }
  }

  private static void requireBoolean(JsonNode parent, String field, boolean expected) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isBoolean() || expected != value.booleanValue()) {
      throw invalid("Retained World preparation " + field + " differs from its exact owner input");
    }
  }

  private static void requireInteger(JsonNode parent, String field, long expected) {
    JsonNode value = parent.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || expected != value.longValue()) {
      throw invalid("Retained World preparation " + field + " differs from its exact owner input");
    }
  }

  private static int intValue(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw invalid("Retained World preparation " + field + " must be an integer");
    }
    return value.intValue();
  }

  private static String receiptDigestFromEvidence(byte[] selectorBytes) {
    return WorldDraftStartLocationEvidence.fromStored(
            WorldPublishedStartLocationEvidence.fromStored(selectorBytes).selectorReceiptBytes())
        .receiptDigest();
  }

  private static String digest(byte[] bytes) {
    return "sha256:" + HexFormat.of().formatHex(sha256(bytes));
  }

  private static String writeClosedEvidenceJson(Object value) {
    try {
      return CLOSED_JSON.writeValueAsString(Objects.requireNonNull(value, "value"));
    } catch (tools.jackson.core.JacksonException invalid) {
      throw new InvalidLifecycleEvidenceException(
          "Original World evidence cannot be encoded for exact preparation comparison", invalid);
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    return Objects.requireNonNull(row.get(field, type), "Persisted " + field + " is null");
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " must not join an ambient transaction");
    }
  }

  private void requireReadOnlyRepeatableReadOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World lifecycle read requires a read-only REPEATABLE READ owner transaction");
    }
    Record state =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT current_setting('transaction_isolation') AS isolation, "
                    + "current_setting('transaction_read_only') AS read_only"),
            "World transaction state query returned no row");
    if (!"repeatable read".equals(state.get("isolation", String.class))
        || !"on".equals(state.get("read_only", String.class))) {
      throw new IllegalStateException(
          "World lifecycle read requires a read-only REPEATABLE READ owner transaction");
    }
  }

  private static InvalidLifecycleEvidenceException invalid(String message) {
    return new InvalidLifecycleEvidenceException(message);
  }

  private record RoomMapping(long runtimeRoomInstanceId) {
    private RoomMapping {
      if (runtimeRoomInstanceId <= 0) throw invalid("Runtime room instance ID must be positive");
    }
  }

  public static final class InvalidLifecycleEvidenceException extends IllegalStateException {
    public InvalidLifecycleEvidenceException(String message) {
      super(message);
    }

    public InvalidLifecycleEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
