package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirementsFamilyCount;
import org.junit.jupiter.api.Test;

class WorldPublishedSpawnRequirementsGrpcCodecTest {
  private static final UUID READ_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER_ID = uuid("44444444-4444-4444-8444-444444444444");

  @Test
  void roundTripsExactRequestCompletePairAndOriginalRequirementValues() {
    var expected = evidence();
    var request = WorldPublishedSpawnRequirementsGrpcCodec.toRequest(expected.request());
    assertThat(WorldPublishedSpawnRequirementsGrpcCodec.fromRequest(request))
        .isEqualTo(expected.request());

    var response =
        WorldPublishedSpawnRequirementsGrpcCodec.toResponse(expected.request(), expected);
    var actual =
        WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(expected.request(), response);

    assertThat(actual).isEqualTo(expected);
    assertThat(actual.spawnRequirements()).hasSize(2);
    assertThat(actual.spawnRequirements().getFirst().revisionOrder()).isEqualTo("9007199254740993");
    assertThat(actual.spawnRequirements().getFirst().spawnCount()).isEqualTo(7);
    assertThat(actual.spawnRequirements().getFirst().respawnDelaySeconds()).isEqualTo(2700);
    assertThat(actual.spawnRequirements().getFirst().entityTemplate())
        .isEqualTo(expected.spawnRequirements().getFirst().entityTemplate());
    assertThat(actual.generationRequirements().getFirst().revisionOrder())
        .isEqualTo("9007199254740992");
    assertThat(actual.generationRequirements().getFirst().value()).isEqualTo(" authored value ");
  }

  @Test
  void rejectsChangedRequestScopeReleaseAndMalformedCapture() {
    var expected = evidence();
    var response =
        WorldPublishedSpawnRequirementsGrpcCodec.toResponse(expected.request(), expected);

    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder().setReadRequestId(OTHER_ID.toString()))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder().setGraphDigest("sha256:" + "f".repeat(64)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graph digest");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setExpectedReleaseAttestationDigest("sha256:" + "f".repeat(64)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("changed the exact source-read request");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setCompleteLaunchBinding(
                            response.getCompleteLaunchBinding().toBuilder()
                                .setReleaseAttestation(
                                    response
                                        .getCompleteLaunchBinding()
                                        .getReleaseAttestation()
                                        .toBuilder()
                                        .setCanonicalTenantId(OTHER_ID.toString())))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setCaptureId("00000000-0000-0000-0000-000000000000")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("captureId");
  }

  @Test
  void rejectsMissingNestedEvidenceUnknownFieldsAndChangedAuthoredScope() {
    var expected = evidence();
    var response =
        WorldPublishedSpawnRequirementsGrpcCodec.toResponse(expected.request(), expected);
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(), response.toBuilder().clearRequest().build()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(), response.toBuilder().clearCompleteLaunchBinding().build()))
        .isInstanceOf(IllegalArgumentException.class);

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(), response.toBuilder().setUnknownFields(unknown).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setRequest(response.getRequest().toBuilder().setUnknownFields(unknown))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setRequest(
                            response.getRequest().toBuilder()
                                .setLaunchBindingRequest(
                                    response.getRequest().getLaunchBindingRequest().toBuilder()
                                        .setUnknownFields(unknown)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setSpawnRequirements(
                            0,
                            response.getSpawnRequirements(0).toBuilder()
                                .setRoomTemplate(
                                    response.getSpawnRequirements(0).getRoomTemplate().toBuilder()
                                        .setTenantId(OTHER_ID.toString())))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical tenant/version context");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    expected.request(),
                    response.toBuilder()
                        .setSpawnRequirements(
                            0,
                            response.getSpawnRequirements(0).toBuilder()
                                .setRoomTemplate(
                                    response.getSpawnRequirements(0).getRoomTemplate().toBuilder()
                                        .setUnknownFields(unknown)))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported fields");
  }

  @Test
  void requiresAllSixExplicitCountsAndRejectsCountListMismatch() {
    var empty = emptyEvidence();
    var emptyResponse = WorldPublishedSpawnRequirementsGrpcCodec.toResponse(empty.request(), empty);
    assertThat(emptyResponse.getFamilyCountsCount()).isEqualTo(6);
    for (var count : emptyResponse.getFamilyCountsList()) {
      assertThat(count.hasCount()).isTrue();
      assertThat(count.getCount()).isZero();
    }
    assertThat(
            WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(empty.request(), emptyResponse))
        .isEqualTo(empty);

    var populated = evidence();
    var response =
        WorldPublishedSpawnRequirementsGrpcCodec.toResponse(populated.request(), populated);
    var badCount =
        WorldPublishedSpawnRequirementsFamilyCount.newBuilder()
            .setFamily(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
            .setCount(1)
            .build();
    assertThatThrownBy(
            () ->
                new WorldPublishedSpawnRequirementsEvidence(
                    populated.request(),
                    populated.launchBinding(),
                    populated.captureId(),
                    populated.graphDigest(),
                    List.of(
                        populated.familyCounts().get(0),
                        populated.familyCounts().get(1),
                        populated.familyCounts().get(2),
                        populated.familyCounts().get(3),
                        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
                            badCount.getFamily(), badCount.getCount()),
                        populated.familyCounts().get(5)),
                    populated.spawnRequirements(),
                    populated.generationRequirements()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("count differs");
    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    populated.request(),
                    response.toBuilder()
                        .setFamilyCounts(4, response.getFamilyCounts(4).toBuilder().setCount(1))
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("count differs");

    assertThatThrownBy(
            () ->
                WorldPublishedSpawnRequirementsGrpcCodec.fromResponse(
                    empty.request(),
                    emptyResponse.toBuilder()
                        .setFamilyCounts(
                            0, emptyResponse.getFamilyCounts(0).toBuilder().clearCount())
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not explicitly present");
  }

  static WorldPublishedSpawnRequirementsEvidence evidence() {
    CompleteLaunchBindingEvidence binding = completeBinding();
    var request = request(binding);
    UUID tenant = binding.releaseAttestation().canonicalTenantId();
    UUID version = binding.releaseAttestation().canonicalVersionId();
    var item =
        new WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference(
            net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType
                .ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM,
            tenant,
            version,
            uuid("66666666-6666-4666-8666-666666666666"));
    var npc =
        new WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference(
            net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType
                .ENTITY_TEMPLATE_REFERENCE_TYPE_NPC,
            tenant,
            version,
            uuid("77777777-7777-4777-8777-777777777777"));
    return new WorldPublishedSpawnRequirementsEvidence(
        request,
        binding,
        uuid("88888888-8888-4888-8888-888888888888"),
        graphDigest(binding),
        counts(1, 2, 3, 4, 2, 2),
        List.of(
            new WorldPublishedSpawnRequirementsEvidence.SpawnRequirement(
                "9007199254740993",
                uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                uuid("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                new RoomTemplateRef(tenant, version, uuid("cccccccc-cccc-4ccc-8ccc-cccccccccccc")),
                item,
                7,
                2700),
            new WorldPublishedSpawnRequirementsEvidence.SpawnRequirement(
                "9007199254740994",
                uuid("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                uuid("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                new RoomTemplateRef(tenant, version, uuid("ffffffff-ffff-4fff-8fff-ffffffffffff")),
                npc,
                0,
                0)),
        List.of(
            new WorldPublishedSpawnRequirementsEvidence.GenerationRequirement(
                "9007199254740992",
                uuid("12345678-1234-4234-8234-123456789001"),
                uuid("12345678-1234-4234-8234-123456789101"),
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
                uuid("12345678-1234-4234-8234-123456789201"),
                "difficulty.mode",
                " authored value "),
            new WorldPublishedSpawnRequirementsEvidence.GenerationRequirement(
                "9007199254740995",
                uuid("12345678-1234-4234-8234-123456789002"),
                uuid("12345678-1234-4234-8234-123456789102"),
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                uuid("12345678-1234-4234-8234-123456789202"),
                "population.mode",
                "sparse")));
  }

  static WorldPublishedSpawnRequirementsEvidence emptyEvidence() {
    CompleteLaunchBindingEvidence binding = completeBinding();
    return new WorldPublishedSpawnRequirementsEvidence(
        request(binding),
        binding,
        uuid("88888888-8888-4888-8888-888888888888"),
        graphDigest(binding),
        counts(0, 0, 0, 0, 0, 0),
        List.of(),
        List.of());
  }

  private static List<WorldPublishedSpawnRequirementsEvidence.FamilyCount> counts(
      int region, int zone, int room, int exit, int generation, int spawn) {
    return List.of(
        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION, region),
        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE, zone),
        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM, room),
        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT, exit),
        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE, generation),
        new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
            spawn));
  }

  private static WorldPublishedSpawnRequirementsEvidence.Request request(
      CompleteLaunchBindingEvidence binding) {
    var descriptor = binding.descriptor();
    GetLaunchDescriptorRequest selector =
        GetLaunchDescriptorRequest.newBuilder()
            .setRequestId(READ_ID.toString())
            .setCanonicalTenantId(descriptor.canonicalTenantId().toString())
            .setWorldSlug(descriptor.worldSlug())
            .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
            .setExpectedRequestDigest(descriptor.requestDigest())
            .setExpectedResultDigest(descriptor.resultDigest())
            .build();
    return new WorldPublishedSpawnRequirementsEvidence.Request(
        descriptor.targetNamespace(),
        READ_ID,
        selector,
        binding.releaseAttestation().evidenceDigest());
  }

  private static String graphDigest(CompleteLaunchBindingEvidence binding) {
    return WorldDraftStartLocationEvidence.fromStored(
            binding.releaseAttestation().worldStartLocationEvidence().selectorReceiptBytes())
        .graphDigest();
  }

  private static CompleteLaunchBindingEvidence completeBinding() {
    WorldPublishedStartLocationEvidence worldStart;
    try {
      worldStart = WorldPublishedStartLocationGrpcCodecTest.evidence();
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
    var selection = worldStart.request();
    var descriptorRequest =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            selection.targetNamespace(),
            "launch-control-request",
            selection.canonicalTenantId(),
            "synthetic-world",
            uuid("99999999-9999-4999-8999-999999999999"),
            "sha256:" + "a".repeat(64),
            19L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            descriptorRequest,
            "descriptor",
            Long.MAX_VALUE,
            false,
            null,
            "{}",
            "generation-config",
            9L,
            7L,
            "published-release",
            false,
            null);
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        selection.appliedCommitId(),
                        "b".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "c".repeat(64) : null))
            .toList();
    AuthoredWorldReleaseAttestationEvidence release =
        AuthoredWorldReleaseAttestationEvidence.create(
            descriptor.targetNamespace(),
            descriptor.resultDigest(),
            descriptor.canonicalTenantId(),
            selection.canonicalVersionId(),
            descriptor.worldSlug(),
            descriptor.authoredWorldSourceOperationId(),
            descriptor.authoredWorldSourceEvidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            selection.publishWorkflowId(),
            selection.appliedCommitId(),
            participants,
            "sha256:" + "d".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of("LOOK"),
            descriptor.generationConfigRevision(),
            worldStart);
    return new CompleteLaunchBindingEvidence(descriptor, release);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
