package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import org.junit.jupiter.api.Test;

class WorldSelectedPublicationArtifactInventoryTest {
  private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID VERSION = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

  @Test
  void acceptsExplicitEmptyRegionInputsAndClosedCanonicalSpawnBinding() {
    var region =
        new WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput(
            UUID.randomUUID(), "", "");
    var spawn =
        new WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "NPC",
            "ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID",
            TENANT,
            VERSION,
            UUID.randomUUID(),
            2,
            30);

    assertThatCode(
            () ->
                WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                    TENANT, VERSION, List.of(region), 0, List.of(spawn)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingOrNullRegionRequirednessInputsInsteadOfTreatingThemAsEmpty() {
    var missingType =
        new WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput(
            UUID.randomUUID(), null, "");
    var missingParams =
        new WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput(
            UUID.randomUUID(), "", null);

    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(missingType), 0, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.UNKNOWN_GRAPH_FAMILY);
    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(missingParams), 0, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.UNKNOWN_GRAPH_FAMILY);
  }

  @Test
  void rejectsOpaqueRegionAndGenerationRuleInputsAsUnrepresentable() {
    var opaqueGenerator =
        new WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput(
            UUID.randomUUID(), "noise-v1", "{\"seed\":9}");

    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(opaqueGenerator), 0, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.REGION_GENERATOR_INPUT);
    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(), 1, List.of()),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.GENERATION_RULE_INPUT);
  }

  @Test
  void rejectsUnsupportedEntityReferenceScopeOrRepresentation() {
    var unsupportedReference =
        new WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "NPC",
            "ENTITY_TEMPLATE_REFERENCE_TYPE_RETAINED_TAG",
            TENANT,
            VERSION,
            UUID.randomUUID(),
            1,
            0);

    assertReason(
        () ->
            WorldSelectedPublicationArtifactInventory.requireSupportedRequiredness(
                TENANT, VERSION, List.of(), 0, List.of(unsupportedReference)),
        WorldSelectedPublicationArtifactInventory.UnrepresentableReason.SPAWN_BINDING_INPUT);
  }

  @Test
  void canonicalLongFieldsRoundTripAsExactDecimalStrings() {
    long beyondBinary64IntegerPrecision = 9_007_199_254_740_993L;
    long maximumLong = Long.MAX_VALUE;
    var envelope = envelopeWithCanonicalLongFields(beyondBinary64IntegerPrecision, maximumLong);
    byte[] canonicalBytes = WorldSelectedPublicationArtifactInventory.encode(envelope);
    String canonicalJson = new String(canonicalBytes, StandardCharsets.UTF_8);

    assertThat(canonicalJson)
        .contains("\"localTenantKey\":\"9007199254740993\"")
        .contains("\"localVersionKey\":\"9223372036854775807\"")
        .contains("\"gameDesignVersionId\":\"9223372036854775807\"")
        .contains("\"sourceGameRowId\":\"9223372036854775807\"")
        .contains("\"versionStateEpoch\":\"9223372036854775807\"");

    var decoded = WorldSelectedPublicationArtifactInventory.decodeCanonicalEnvelope(canonicalBytes);
    var originalInventory = new WorldSelectedPublicationArtifactInventory(envelope, canonicalBytes);
    var decodedInventory = new WorldSelectedPublicationArtifactInventory(decoded, canonicalBytes);
    assertThat(decoded.ownerScope().localTenantKey())
        .isEqualTo(Long.toString(beyondBinary64IntegerPrecision));
    assertThat(decoded.ownerScope().localVersionKey()).isEqualTo(Long.toString(maximumLong));
    assertThat(decoded.sourceIntake().sourceGameRowId()).isEqualTo(Long.toString(maximumLong));
    assertThat(decoded.freeze().versionStateEpoch()).isEqualTo(Long.toString(maximumLong));
    assertThat(decodedInventory.publicEvidence().freeze().versionStateEpoch())
        .isEqualTo(Long.toString(maximumLong));
    assertThat(new String(decodedInventory.publicCanonicalBytes(), StandardCharsets.UTF_8))
        .contains("\"versionStateEpoch\":\"9223372036854775807\"");
    assertThat(decodedInventory.publicDigest()).isEqualTo(originalInventory.publicDigest());

    String zeroPaddedEpoch =
        canonicalJson.replace(
            "\"versionStateEpoch\":\"9223372036854775807\"",
            "\"versionStateEpoch\":\"09223372036854775807\"");
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventory.decodeCanonicalEnvelope(
                    zeroPaddedEpoch.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    String floatingEpoch =
        canonicalJson.replace(
            "\"versionStateEpoch\":\"9223372036854775807\"",
            "\"versionStateEpoch\":9223372036854775807.0");
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventory.decodeCanonicalEnvelope(
                    floatingEpoch.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
  }

  private static WorldSelectedPublicationArtifactInventory.Envelope envelopeWithCanonicalLongFields(
      long beyondBinary64IntegerPrecision, long maximumLong) {
    var complete = envelopeWithReversePublicInputOrder();
    var owner = complete.ownerScope();
    return new WorldSelectedPublicationArtifactInventory.Envelope(
        complete.schema(),
        complete.schemaVersion(),
        complete.completeness(),
        new WorldSelectedPublicationArtifactInventory.OwnerScope(
            owner.targetNamespace(),
            owner.canonicalTenantId(),
            owner.canonicalVersionId(),
            Long.toString(beyondBinary64IntegerPrecision),
            Long.toString(maximumLong),
            owner.versionIdentityOperationId(),
            Long.toString(maximumLong),
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest()),
        new WorldSelectedPublicationArtifactInventory.SourceIntake(
            complete.sourceIntake().worldSlug(),
            Long.toString(maximumLong),
            complete.sourceIntake().sourceGameTenantKey(),
            complete.sourceIntake().sourceProvenanceKind()),
        complete.selectedApplication(),
        complete.accountOrder(),
        new WorldSelectedPublicationArtifactInventoryEvidence.Freeze(
            complete.freeze().publicationFence(),
            complete.freeze().publicationRequestId(),
            complete.freeze().requestDigest(),
            Long.toString(maximumLong),
            complete.freeze().publishWorkflowId()),
        complete.checkpoint(),
        complete.sourceModel(),
        complete.artifactDecisions());
  }

  @Test
  void publicProjectionSortsTypedInputKeysWithoutRewritingRetainedPrivateEnvelope() {
    var envelope = envelopeWithReversePublicInputOrder();
    byte[] storedBytes = WorldSelectedPublicationArtifactInventory.encode(envelope);
    var inventory = new WorldSelectedPublicationArtifactInventory(envelope, storedBytes);

    assertThat(inventory.envelope().sourceModel().regionGeneratorInputs())
        .extracting(
            WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput
                ::regionTemplateId)
        .containsExactly(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"));
    assertThat(inventory.envelope().sourceModel().spawnBindingInputs())
        .extracting(
            WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput::bindingTemplateId)
        .containsExactly(
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"));

    var publicModel = inventory.publicEvidence().sourceModel();
    assertThat(publicModel.regionGeneratorInputs())
        .extracting(
            WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput
                ::regionTemplateId)
        .containsExactly(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    assertThat(publicModel.spawnBindingInputs())
        .extracting(
            WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput::bindingTemplateId)
        .containsExactly(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    assertThat(inventory.canonicalBytes()).containsExactly(storedBytes);
  }

  @Test
  void closureProfileRetainsExplicitSourceAndRequiresSelectedDigestSchema4() {
    var envelope = envelopeWithInboundClosure();
    var inventory =
        new WorldSelectedPublicationArtifactInventory(
            envelope, WorldSelectedPublicationArtifactInventory.encode(envelope));

    assertThat(inventory.envelope().schema())
        .isEqualTo(WorldSelectedPublicationArtifactInventory.INBOUND_CLOSURE_SCHEMA);
    assertThat(inventory.envelope().sourceModel().inboundSourceClosure().familyCounts())
        .extracting(
            WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount::count)
        .containsExactly(0, 0, 0, 0, 0, 0, 0);
    assertThat(inventory.publicEvidence().checkpoint().digestSchemaVersion()).isEqualTo(4);
    assertThat(new String(inventory.publicCanonicalBytes(), StandardCharsets.UTF_8))
        .contains(
            "world-selected-publication-artifact-inventory/v2",
            "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_INBOUND_EMPTY_V1",
            "inboundSourceClosure");

    var wrongCheckpoint =
        new WorldSelectedPublicationArtifactInventory.Envelope(
            envelope.schema(),
            envelope.schemaVersion(),
            envelope.completeness(),
            envelope.ownerScope(),
            envelope.sourceIntake(),
            envelope.selectedApplication(),
            envelope.accountOrder(),
            envelope.freeze(),
            new WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint(
                envelope.checkpoint().appliedCommitId(), envelope.checkpoint().contentDigest(), 3),
            envelope.sourceModel(),
            envelope.artifactDecisions());
    assertThatThrownBy(
            () ->
                new WorldSelectedPublicationArtifactInventory(
                        wrongCheckpoint,
                        WorldSelectedPublicationArtifactInventory.encode(wrongCheckpoint))
                    .publicEvidence())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("participant digest schema");
  }

  private static WorldSelectedPublicationArtifactInventory.Envelope envelopeWithInboundClosure() {
    var prior = envelopeWithReversePublicInputOrder();
    var model = prior.sourceModel();
    var closure =
        new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration(
            1,
            List.of(
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT"),
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT"),
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION"),
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING"),
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK"),
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE"),
                inboundFamily("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING")));
    var source =
        new WorldSelectedPublicationArtifactInventoryEvidence.SourceModel(
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL,
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
            model.graphDigest(),
            model.topologyResultDigest(),
            model.familyCounts(),
            model.regionGeneratorInputs(),
            model.generationRuleFields(),
            model.regionFields(),
            model.zoneFields(),
            model.roomFields(),
            model.roomExitFields(),
            model.spawnBindingFields(),
            model.spawnBindingInputs(),
            model.generationRuleInputCount(),
            model.spawnBindingCount(),
            model.appliedEpochs(),
            closure);
    return new WorldSelectedPublicationArtifactInventory.Envelope(
        WorldSelectedPublicationArtifactInventory.INBOUND_CLOSURE_SCHEMA,
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION,
        prior.completeness(),
        prior.ownerScope(),
        prior.sourceIntake(),
        prior.selectedApplication(),
        prior.accountOrder(),
        prior.freeze(),
        new WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint(
            prior.checkpoint().appliedCommitId(), prior.checkpoint().contentDigest(), 4),
        source,
        prior.artifactDecisions());
  }

  private static WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount
      inboundFamily(String family) {
    return new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
        family, 0);
  }

  private static WorldSelectedPublicationArtifactInventory.Envelope
      envelopeWithReversePublicInputOrder() {
    var sourceModel =
        new WorldSelectedPublicationArtifactInventoryEvidence.SourceModel(
            "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1",
            2,
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            List.of(
                new WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount("REGION", 2),
                new WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount("ZONE", 1),
                new WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount("ROOM", 1),
                new WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount("ROOM_EXIT", 0),
                new WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount(
                    "GENERATION_RULE", 0),
                new WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount(
                    "WORLD_ENTITY_SPAWN_BINDING", 2)),
            List.of(
                new WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput(
                    UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), "", ""),
                new WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput(
                    UUID.fromString("11111111-1111-4111-8111-111111111111"), "", "")),
            List.of("id", "name", "scopeType", "scopeId", "value"),
            List.of(
                "id",
                "shardId",
                "name",
                "weather",
                "generationSeed",
                "generatorType",
                "generatorParams",
                "spacingMultiplier"),
            List.of("id", "regionId", "name"),
            List.of(
                "id",
                "zoneId",
                "name",
                "description",
                "nameLocalizedVariantsJson",
                "descriptionLocalizedVariantsJson"),
            List.of("id", "fromRoomId", "toRoomId", "direction", "cost"),
            List.of(
                "id",
                "roomId",
                "entityTemplateType",
                "entityReference.kind",
                "entityReference.tenantId",
                "entityReference.versionId",
                "entityReference.templateId",
                "spawnCount",
                "respawnDelaySeconds"),
            List.of(
                spawnBinding("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                spawnBinding("22222222-2222-4222-8222-222222222222")),
            0,
            2,
            List.of(new AppliedEpoch("ROOM", "room-1", "ZONE", "zone-1", "1", "2")));
    return new WorldSelectedPublicationArtifactInventory.Envelope(
        WorldSelectedPublicationArtifactInventory.SCHEMA,
        1,
        "COMPLETE",
        new WorldSelectedPublicationArtifactInventory.OwnerScope(
            "world",
            TENANT,
            VERSION,
            "9007199254740993",
            "9223372036854775807",
            UUID.randomUUID(),
            "9223372036854775807",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "sha256:" + "1".repeat(64),
            UUID.randomUUID(),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64)),
        new WorldSelectedPublicationArtifactInventory.SourceIntake(
            "world", "9223372036854775807", "source-tenant", "CANONICAL"),
        new WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            "sha256:" + "4".repeat(64),
            "sha256:" + "5".repeat(64)),
        new WorldSelectedPublicationArtifactInventory.AccountOrder(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "publish",
            "sha256:" + "6".repeat(64),
            UUID.randomUUID().toString(),
            "sha256:" + "7".repeat(64),
            new byte[] {1}),
        new WorldSelectedPublicationArtifactInventoryEvidence.Freeze(
            UUID.randomUUID(), "publish", "8".repeat(64), "9", "workflow"),
        new WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint(
            UUID.randomUUID().toString(), "9".repeat(64), 3),
        sourceModel,
        List.of(
            new WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision(
                "NAVMESH", "NOT_REQUIRED", "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
            new WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision(
                "PATH_GRAPH",
                "NOT_REQUIRED",
                "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
  }

  private static WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput spawnBinding(
      String bindingId) {
    return new WorldSelectedPublicationArtifactInventoryEvidence.SpawnBindingInput(
        UUID.fromString(bindingId),
        UUID.randomUUID(),
        "ITEM",
        "ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID",
        TENANT,
        VERSION,
        UUID.randomUUID(),
        1,
        0);
  }

  private static void assertReason(
      Runnable action, WorldSelectedPublicationArtifactInventory.UnrepresentableReason expected) {
    Throwable failure = catchThrowable(action::run);
    assertThat(failure)
        .isInstanceOfSatisfying(
            WorldSelectedPublicationArtifactInventory.UnrepresentableRequirednessException.class,
            exception -> assertThat(exception.reason()).isEqualTo(expected));
  }
}
