package net.firedevops.firemud.test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/**
 * ISOLATED stipulated external COMPLETE evidence for local consumer tests only. This does not
 * execute World's source inventory producer or establish actual graph requiredness/completeness.
 * Actual World persistence tests must use their real retained inventory instead.
 */
public final class IsolatedWorldPublicationInventoryFixtures {
  private IsolatedWorldPublicationInventoryFixtures() {}

  public static WorldSelectedPublicationArtifactInventoryEvidence stipulated(
      AccountPublicationAuthorizationBinding account, WorldPublishedStartLocationEvidence world) {
    var selected = account.input().selection();
    var commit = selected.selectedCommit();
    var request = world.request();
    var original = DraftAuthorizationFenceBinding.fromStored(world.originalAccountBindingBytes());
    var graph =
        WorldDraftStartLocationEvidence.fromStored(world.selectorReceiptBytes()).graphDigest();
    boolean closureQualified = request.digestSchemaVersion() == 4;
    String inventorySchema =
        closureQualified
            ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA
            : WorldSelectedPublicationArtifactInventoryEvidence.SCHEMA;
    int inventorySchemaVersion =
        closureQualified
            ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION
            : WorldSelectedPublicationArtifactInventoryEvidence.SCHEMA_VERSION;
    String sourceModel =
        closureQualified
            ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL
            : WorldSelectedPublicationArtifactInventoryEvidence.SOURCE_MODEL;
    int graphSchemaVersion =
        closureQualified
            ? WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION
            : 2;
    var inboundClosure =
        closureQualified
            ? new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration(
                1,
                List.of(
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 0),
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
                    new WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount(
                        "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0)))
            : null;
    UUID fixtureId =
        UUID.nameUUIDFromBytes("ISOLATED World inventory input".getBytes(StandardCharsets.UTF_8));
    var evidence =
        new PublicEvidence(
            inventorySchema,
            inventorySchemaVersion,
            "COMPLETE",
            new PublicOwnerScope(
                request.targetNamespace(),
                request.canonicalTenantId(),
                request.canonicalVersionId(),
                fixtureId,
                request.intakeRequestId(),
                fixtureId,
                sha256(world.originalAccountBindingBytes()),
                fixtureId,
                sha256(world.selectorReceiptBytes()),
                sha256(world.selectorReceiptBytes())),
            new SelectedApplication(
                original.operationId(),
                commit.requestId(),
                commit.commitId().toString(),
                commit.digest(),
                sha256(world.appliedResultBytes())),
            new PublicAccountOrder(
                account.operationId(),
                account.fenceId(),
                account.input().actorAccountId(),
                selected.intent().publishRequestId(),
                selected.digest(),
                commit.commitId().toString(),
                sha256(account.canonicalBytes())),
            new Freeze(
                request.publicationFence(),
                request.publicationRequestId(),
                request.requestDigest(),
                Long.toString(request.versionStateEpoch()),
                request.publishWorkflowId()),
            new Checkpoint(
                request.appliedCommitId(), request.contentDigest(), request.digestSchemaVersion()),
            new SourceModel(
                sourceModel,
                graphSchemaVersion,
                graph,
                sha256("ISOLATED topology result".getBytes(StandardCharsets.UTF_8)),
                List.of(
                    new FamilyCount("REGION", 1),
                    new FamilyCount("ZONE", 1),
                    new FamilyCount("ROOM", 1),
                    new FamilyCount("ROOM_EXIT", 0),
                    new FamilyCount("GENERATION_RULE", 0),
                    new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", 0)),
                List.of(new RegionGeneratorInput(fixtureId, "", "")),
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
                List.of(),
                0,
                0,
                commit.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
                    .map(
                        unit ->
                            new AppliedEpoch(
                                unit.aggregateType(),
                                unit.aggregateId(),
                                unit.scopeType(),
                                unit.scopeId(),
                                unit.expectedEpoch(),
                                new BigInteger(unit.expectedEpoch())
                                    .add(BigInteger.ONE)
                                    .toString()))
                    .toList(),
                inboundClosure),
            List.of(
                new ArtifactDecision(
                    "NAVMESH",
                    "NOT_REQUIRED",
                    "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
                new ArtifactDecision(
                    "PATH_GRAPH",
                    "NOT_REQUIRED",
                    "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
    return WorldSelectedPublicationArtifactInventoryEvidence.fromRetainedSelection(
        account, world, evidence.canonicalBytes(), evidence.digest());
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
