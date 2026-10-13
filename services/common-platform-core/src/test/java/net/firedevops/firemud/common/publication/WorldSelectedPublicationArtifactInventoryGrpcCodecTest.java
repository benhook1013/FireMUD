package net.firedevops.firemud.common.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;
import org.junit.jupiter.api.Test;

class WorldSelectedPublicationArtifactInventoryGrpcCodecTest {
  @Test
  void roundTripsTypedPublicInventoryBoundToTheExactFreezeWithoutPrivateOwnerKeys() {
    var fixture = fixture();
    var evidence =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            fixture.freezeEvidence(), fixture.publicEvidence());
    var response = WorldSelectedPublicationArtifactInventoryGrpcCodec.toResponse(evidence);
    var decoded =
        WorldSelectedPublicationArtifactInventoryGrpcCodec.fromResponse(
            fixture.freezeEvidence(), response);

    assertThat(decoded.canonicalBytes()).containsExactly(evidence.canonicalBytes());
    assertThat(decoded.digest()).isEqualTo(evidence.digest());
    assertThat(decoded.publicEvidence()).isEqualTo(fixture.publicEvidence());
    assertThat(new String(decoded.canonicalBytes(), StandardCharsets.UTF_8))
        .doesNotContain(
            "localTenantKey", "localVersionKey", "gameDesignVersionId", "sourceGameRowId");
    var request =
        WorldSelectedPublicationArtifactInventoryGrpcCodec.toRequest(fixture.freezeEvidence());
    var decodedFreeze = WorldSelectedPublicationArtifactInventoryGrpcCodec.fromRequest(request);
    assertThat(WorldSelectedPublicationArtifactInventoryGrpcCodec.toRequest(decodedFreeze))
        .isEqualTo(request);
    assertThat(decodedFreeze.request()).isEqualTo(fixture.freezeEvidence().request());
    assertThat(decodedFreeze.acknowledgement())
        .isEqualTo(fixture.freezeEvidence().acknowledgement());
  }

  @Test
  void roundTripsVersionedAuthoredEmptyInboundClosureThroughPublicCodec() {
    var fixture = closureFixture();
    var publicEvidence = fixture.publicEvidence();
    var evidence =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            fixture.freezeEvidence(), publicEvidence);
    var response = WorldSelectedPublicationArtifactInventoryGrpcCodec.toResponse(evidence);
    var decoded =
        WorldSelectedPublicationArtifactInventoryGrpcCodec.fromResponse(
            fixture.freezeEvidence(), response);

    assertThat(decoded.publicEvidence()).isEqualTo(publicEvidence);
    assertThat(decoded.publicEvidence().schema())
        .isEqualTo(WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA);
    assertThat(decoded.publicEvidence().sourceModel().graphSchemaVersion()).isEqualTo(3);
    assertThat(decoded.publicEvidence().sourceModel().inboundSourceClosure().familyCounts())
        .extracting(InboundSourceFamilyCount::count)
        .containsExactly(0, 0, 0, 0, 0, 0, 0);
    assertThat(new String(decoded.canonicalBytes(), StandardCharsets.UTF_8))
        .contains("inboundSourceClosure", "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING")
        .doesNotContain("localTenantKey", "localVersionKey", "sourceGameRowId");
    String opaqueClosure =
        new String(decoded.canonicalBytes(), StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"members\":[]");
    assertRejectedPublicJson(fixture, opaqueClosure);
  }

  @Test
  void versionedInboundClosureRejectsMissingUnknownAndNonemptyFamilies() {
    var fixture = closureFixture();
    var original = fixture.publicEvidence();
    var inbound = original;
    var model = inbound.sourceModel();

    var nonempty =
        new InboundSourceClosureDeclaration(
            1,
            List.of(
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 1),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0)));
    var unknown =
        new InboundSourceClosureDeclaration(
            1,
            List.of(
                new InboundSourceFamilyCount("UNKNOWN_FAMILY", 0),
                model.inboundSourceClosure().familyCounts().get(1),
                model.inboundSourceClosure().familyCounts().get(2),
                model.inboundSourceClosure().familyCounts().get(3),
                model.inboundSourceClosure().familyCounts().get(4),
                model.inboundSourceClosure().familyCounts().get(5),
                model.inboundSourceClosure().familyCounts().get(6)));

    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
                    fixture.freezeEvidence(), withInboundClosure(inbound, nonempty)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported nonempty");
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
                    fixture.freezeEvidence(), withInboundClosure(inbound, unknown)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not canonical");
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
                    fixture.freezeEvidence(), withInboundClosure(inbound, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source model is unsupported or incomplete");
  }

  @Test
  void rejectsChangedFreezeDigestUnknownFieldsAndNoncanonicalOrOpenPublicBytes() {
    var fixture = fixture();
    var evidence =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            fixture.freezeEvidence(), fixture.publicEvidence());
    var response = WorldSelectedPublicationArtifactInventoryGrpcCodec.toResponse(evidence);
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryGrpcCodec.fromResponse(
                    fixture.freezeEvidence(),
                    response.toBuilder()
                        .setPublicInventoryDigest("sha256:" + "0".repeat(64))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryGrpcCodec.fromResponse(
                    fixture.freezeEvidence(),
                    response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");

    var original = fixture.publicEvidence();
    var owner = original.ownerScope();
    var changedOwner =
        new PublicOwnerScope(
            owner.targetNamespace(),
            uuid("17171717-1717-4717-8717-171717171717"),
            owner.canonicalVersionId(),
            owner.versionIdentityOperationId(),
            owner.intakeRequestId(),
            owner.intakeOperationId(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest());
    var changedEvidence =
        new PublicEvidence(
            original.schema(),
            original.schemaVersion(),
            original.completeness(),
            changedOwner,
            original.selectedApplication(),
            original.accountOrder(),
            original.freeze(),
            original.checkpoint(),
            original.sourceModel(),
            original.artifactDecisions());
    byte[] changedBytes = changedEvidence.canonicalBytes();
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryEvidence.fromCanonicalBytes(
                    fixture.freezeEvidence(), changedBytes, sha256(changedBytes)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("differs from exact selected Account order or freeze checkpoint");

    String canonical = new String(evidence.canonicalBytes(), StandardCharsets.UTF_8);
    String unknownJson = canonical.replaceFirst("\\{", "{\"unexpected\":true,");
    assertRejectedPublicJson(fixture, unknownJson);
    String duplicateJson = canonical.replaceFirst("\\{", "{\"schema\":\"ignored\",");
    assertRejectedPublicJson(fixture, duplicateJson);
    assertRejectedPublicJson(fixture, canonical + "{}");
    assertRejectedPublicJson(
        fixture,
        canonical.replace(
            "\"versionStateEpoch\":\""
                + fixture.freezeEvidence().acknowledgement().versionStateEpoch()
                + "\"",
            "\"versionStateEpoch\":"
                + fixture.freezeEvidence().acknowledgement().versionStateEpoch()));
    assertRejectedPublicJson(
        fixture,
        canonical.replace(
            "\"versionStateEpoch\":\""
                + fixture.freezeEvidence().acknowledgement().versionStateEpoch()
                + "\"",
            "\"versionStateEpoch\":\"01\""));

    String region =
        "{\"generatorParams\":\"\",\"generatorType\":\"\","
            + "\"regionTemplateId\":\"15151515-1515-4515-8515-151515151515\"}";
    String duplicateRegion =
        canonical
            .replace("\"family\":\"REGION\",\"rowCount\":1", "\"family\":\"REGION\",\"rowCount\":2")
            .replace(
                "\"regionGeneratorInputs\":[" + region + "]",
                "\"regionGeneratorInputs\":[" + region + "," + region + "]");
    assertRejectedPublicJson(fixture, duplicateRegion);
    String higherRegion =
        region.replace(
            "15151515-1515-4515-8515-151515151515", "25252525-2525-4525-8525-252525252525");
    String reversedRegions =
        canonical
            .replace("\"family\":\"REGION\",\"rowCount\":1", "\"family\":\"REGION\",\"rowCount\":2")
            .replace(
                "\"regionGeneratorInputs\":[" + region + "]",
                "\"regionGeneratorInputs\":[" + higherRegion + "," + region + "]");
    assertRejectedPublicJson(fixture, reversedRegions);

    String spawn =
        "{\"bindingTemplateId\":\"18181818-1818-4818-8818-181818181818\","
            + "\"entityReferenceKind\":\"ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID\","
            + "\"entityTemplateId\":\"19191919-1919-4919-8919-191919191919\","
            + "\"entityTemplateType\":\"ITEM\","
            + "\"entityTenantId\":\""
            + fixture.publicEvidence().ownerScope().canonicalTenantId()
            + "\",\"entityVersionId\":\""
            + fixture.publicEvidence().ownerScope().canonicalVersionId()
            + "\",\"respawnDelaySeconds\":0,"
            + "\"roomTemplateId\":\"20202020-2020-4020-8020-202020202020\","
            + "\"spawnCount\":1}";
    String duplicateSpawn =
        canonical
            .replace(
                "\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":0",
                "\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":2")
            .replace("\"spawnBindingCount\":0", "\"spawnBindingCount\":2")
            .replace(
                "\"spawnBindingInputs\":[]",
                "\"spawnBindingInputs\":[" + spawn + "," + spawn + "]");
    assertRejectedPublicJson(fixture, duplicateSpawn);
    String higherSpawn =
        spawn.replace(
            "18181818-1818-4818-8818-181818181818", "25252525-2525-4525-8525-252525252525");
    String reversedSpawns =
        canonical
            .replace(
                "\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":0",
                "\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":2")
            .replace("\"spawnBindingCount\":0", "\"spawnBindingCount\":2")
            .replace(
                "\"spawnBindingInputs\":[]",
                "\"spawnBindingInputs\":[" + higherSpawn + "," + spawn + "]");
    assertRejectedPublicJson(fixture, reversedSpawns);

    var model = fixture.publicEvidence().sourceModel();
    var unsupportedModel =
        new SourceModel(
            "UNSUPPORTED_MODEL",
            model.graphSchemaVersion(),
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
            model.appliedEpochs());
    assertThatThrownBy(
            () ->
                new PublicEvidence(
                    fixture.publicEvidence().schema(),
                    fixture.publicEvidence().schemaVersion(),
                    fixture.publicEvidence().completeness(),
                    fixture.publicEvidence().ownerScope(),
                    fixture.publicEvidence().selectedApplication(),
                    fixture.publicEvidence().accountOrder(),
                    fixture.publicEvidence().freeze(),
                    fixture.publicEvidence().checkpoint(),
                    unsupportedModel,
                    fixture.publicEvidence().artifactDecisions()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source model is unsupported or incomplete");
  }

  static Fixture fixture() {
    var request = WorldSelectedDraftPublicationFreezeGrpcCodecTest.request();
    var acknowledgement = acknowledgement(request);
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            request, WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    var selected = request.accountBinding().input().selection();
    var commit = selected.selectedCommit();
    var account = request.accountBinding();
    var owner =
        new PublicOwnerScope(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.canonicalVersionId(),
            uuid("12121212-1212-4212-8212-121212121212"),
            acknowledgement.intakeRequestId(),
            uuid("13131313-1313-4313-8313-131313131313"),
            "sha256:" + "1".repeat(64),
            uuid("14141414-1414-4414-8414-141414141414"),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    var sourceModel =
        new SourceModel(
            "WORLD_LOGICAL_ROOM_EXIT_ADJACENCY_V1",
            2,
            "sha256:" + "4".repeat(64),
            "sha256:" + "5".repeat(64),
            List.of(
                new FamilyCount("REGION", 1),
                new FamilyCount("ZONE", 1),
                new FamilyCount("ROOM", 1),
                new FamilyCount("ROOM_EXIT", 0),
                new FamilyCount("GENERATION_RULE", 0),
                new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", 0)),
            List.of(new RegionGeneratorInput(uuid("15151515-1515-4515-8515-151515151515"), "", "")),
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
                            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
                .toList());
    var publicEvidence =
        new PublicEvidence(
            WorldSelectedPublicationArtifactInventoryEvidence.SCHEMA,
            1,
            "COMPLETE",
            owner,
            new SelectedApplication(
                uuid("16161616-1616-4616-8616-161616161616"),
                commit.requestId(),
                commit.commitId().toString(),
                commit.digest(),
                "sha256:" + "6".repeat(64)),
            new PublicAccountOrder(
                account.operationId(),
                account.fenceId(),
                account.input().actorAccountId(),
                selected.intent().publishRequestId(),
                selected.digest(),
                commit.commitId().toString(),
                sha256(account.canonicalBytes())),
            new Freeze(
                acknowledgement.publicationFence(),
                request.publicationRequestId(),
                request.requestDigest(),
                Long.toString(acknowledgement.versionStateEpoch()),
                PublicationDigestRequestBinding.full(
                        request.canonicalTenantId().toString(),
                        Long.toString(selected.target().gameDesignVersionRowId()),
                        request.publicationRequestId())
                    .derivedWorkflowIdentity()),
            new Checkpoint(
                acknowledgement.appliedCommitId(),
                acknowledgement.contentDigest(),
                acknowledgement.digestSchemaVersion()),
            sourceModel,
            List.of(
                new ArtifactDecision(
                    "NAVMESH",
                    "NOT_REQUIRED",
                    "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
                new ArtifactDecision(
                    "PATH_GRAPH",
                    "NOT_REQUIRED",
                    "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
    return new Fixture(freezeEvidence, publicEvidence);
  }

  private static PublicEvidence inboundClosureEvidence(PublicEvidence original) {
    return withInboundClosure(
        original,
        new InboundSourceClosureDeclaration(
            1,
            List.of(
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0))));
  }

  private static Fixture closureFixture() {
    var base = fixture();
    var prior = base.freezeEvidence().acknowledgement();
    var acknowledgement =
        new Acknowledgement(
            prior.request(),
            prior.intakeRequestId(),
            prior.versionStateEpoch(),
            prior.publicationFence(),
            prior.ownerFreezePhase(),
            prior.appliedCommitId(),
            prior.contentDigest(),
            4);
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            prior.request(),
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    return new Fixture(freezeEvidence, inboundClosureEvidence(base.publicEvidence()));
  }

  private static PublicEvidence withInboundClosure(
      PublicEvidence original, InboundSourceClosureDeclaration inboundClosure) {
    var prior = original.sourceModel();
    var source =
        new SourceModel(
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL,
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
            prior.graphDigest(),
            prior.topologyResultDigest(),
            prior.familyCounts(),
            prior.regionGeneratorInputs(),
            prior.generationRuleFields(),
            prior.regionFields(),
            prior.zoneFields(),
            prior.roomFields(),
            prior.roomExitFields(),
            prior.spawnBindingFields(),
            prior.spawnBindingInputs(),
            prior.generationRuleInputCount(),
            prior.spawnBindingCount(),
            prior.appliedEpochs(),
            inboundClosure);
    return new PublicEvidence(
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA,
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION,
        original.completeness(),
        original.ownerScope(),
        original.selectedApplication(),
        original.accountOrder(),
        original.freeze(),
        new Checkpoint(
            original.checkpoint().appliedCommitId(), original.checkpoint().contentDigest(), 4),
        source,
        original.artifactDecisions());
  }

  private static void assertRejectedPublicJson(Fixture fixture, String json) {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                WorldSelectedPublicationArtifactInventoryEvidence.fromCanonicalBytes(
                    fixture.freezeEvidence(), bytes, sha256(bytes)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Acknowledgement acknowledgement(
      WorldSelectedDraftPublicationFreezeEvidence.Request request) {
    return new Acknowledgement(
        request,
        uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        request.expectedVersionStateEpoch(),
        uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        OwnerFreezePhase.FROZEN,
        request.accountBinding().input().selection().selectedCommit().commitId().toString(),
        "a".repeat(64),
        3);
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  record Fixture(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence, PublicEvidence publicEvidence) {}
}
