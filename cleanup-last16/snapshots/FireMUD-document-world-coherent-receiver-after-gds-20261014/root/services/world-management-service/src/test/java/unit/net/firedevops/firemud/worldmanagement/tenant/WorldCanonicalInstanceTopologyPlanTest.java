package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Row;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Template;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.Entry;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.GenerationRuleIntent;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.SpawnBindingIntent;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.junit.jupiter.api.Test;

/**
 * These fixtures exercise the pure projection with synthetic frozen component values. They do not
 * establish authenticated publication, persisted capture, release evidence, or instance writes.
 */
class WorldCanonicalInstanceTopologyPlanTest {
  private static final UUID TENANT = uuid(1);
  private static final UUID VERSION = uuid(2);
  private static final UUID COMMIT = uuid(3);
  private static final UUID REGION_A = uuid(10);
  private static final UUID REGION_B = uuid(11);
  private static final UUID ZONE_A1 = uuid(20);
  private static final UUID ZONE_A2 = uuid(21);
  private static final UUID ZONE_B = uuid(22);
  private static final UUID ROOM_A1 = uuid(30);
  private static final UUID ROOM_A2 = uuid(31);
  private static final UUID ROOM_B = uuid(32);
  private static final UUID EXIT_A = uuid(40);
  private static final UUID RULE_A = uuid(50);
  private static final UUID RULE_B = uuid(51);
  private static final UUID SPAWN_A = uuid(60);
  private static final UUID SPAWN_B = uuid(61);
  private static final UUID ENTITY_ITEM = uuid(70);
  private static final UUID ENTITY_NPC = uuid(71);
  private static final byte[] GRAPH_BYTES =
      "synthetic graph bytes".getBytes(StandardCharsets.UTF_8);
  private static final byte[] STORAGE_BYTES =
      "synthetic storage result".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESULT_BYTES =
      "synthetic capture result".getBytes(StandardCharsets.UTF_8);

  @Test
  void projectsEveryFamilyWithAuthoredParentLinksAndExactRequiredIntents() throws Exception {
    Fixture fixture = fixture();
    var projected = WorldCanonicalInstanceTopologyPlan.create(fixture.source());

    assertThat(projected.regions()).hasSize(2);
    assertThat(projected.zones()).hasSize(3);
    assertThat(projected.rooms()).hasSize(3);
    assertThat(projected.roomExits()).hasSize(1);
    assertThat(projected.generationRules()).hasSize(2);
    assertThat(projected.spawnBindings()).hasSize(2);
    assertThat(projected.roomExits().getFirst().fromRoom())
        .isEqualTo(new Template(Family.ROOM, ROOM_A1));
    assertThat(projected.roomExits().getFirst().toRoom())
        .isEqualTo(new Template(Family.ROOM, ROOM_A2));
    assertThat(projected.roomExits().getFirst().source().identity())
        .isEqualTo(new Template(Family.ROOM_EXIT, EXIT_A));
    assertThat(projected.zones())
        .filteredOn(zone -> zone.identity().templateId().equals(ZONE_B))
        .singleElement()
        .satisfies(
            zone -> assertThat(zone.region()).isEqualTo(new Template(Family.REGION, REGION_B)));
    assertThat(projected.rooms())
        .filteredOn(room -> room.identity().templateId().equals(ROOM_B))
        .singleElement()
        .satisfies(room -> assertThat(room.zone()).isEqualTo(new Template(Family.ZONE, ZONE_B)));

    assertThat(projected.generationRules())
        .extracting(GenerationRuleIntent::requiredIntent)
        .containsExactly(
            GenerationRuleDesignMutation.newBuilder()
                .setName("weather-profile")
                .setValue("{ \"locale\" : \"Café\" }")
                .build(),
            GenerationRuleDesignMutation.newBuilder()
                .setName("room-density")
                .setValue("sparse")
                .build());
    assertThat(projected.spawnBindings())
        .extracting(SpawnBindingIntent::requiredIntent)
        .containsExactly(
            WorldEntitySpawnBindingDesignMutation.newBuilder()
                .setRoomId(ROOM_A1.toString())
                .setEntityTemplateType(
                    EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM)
                .setEntityTemplateId(ENTITY_ITEM.toString())
                .setSpawnCount(0)
                .setRespawnDelaySeconds(300)
                .build(),
            WorldEntitySpawnBindingDesignMutation.newBuilder()
                .setRoomId(ROOM_B.toString())
                .setEntityTemplateType(
                    EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                .setEntityTemplateId(ENTITY_NPC.toString())
                .setSpawnCount(4)
                .setRespawnDelaySeconds(60)
                .build());
    assertThat(
            projected
                .spawnBindings()
                .getFirst()
                .source()
                .content()
                .getWorldEntitySpawnBinding()
                .getSpawnCount())
        .isEqualTo(1);
    assertThat(projected.spawnBindings().getFirst().entityReference().kind())
        .isEqualTo(EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM);
    assertThat(projected.spawnBindings().getFirst().entityReference().tenantId()).isEqualTo(TENANT);
    assertThat(projected.spawnBindings().getFirst().entityReference().versionId())
        .isEqualTo(VERSION);
    assertThat(projected.spawnBindings().getFirst().entityReference().templateId())
        .isEqualTo(ENTITY_ITEM);
  }

  @Test
  void keepsSourceBindingPayloadBytesAndOrderingAcrossDeterministicRetries() throws Exception {
    Fixture fixture = fixture();
    var first = WorldCanonicalInstanceTopologyPlan.create(fixture.source());
    var retry = WorldCanonicalInstanceTopologyPlan.create(fixture.source());

    assertThat(first.captureId()).isEqualTo(uuid(100));
    assertThat(fixture.source().status()).isEqualTo(WorldCanonicalFrozenTopology.STATUS);
    assertThat(first.sourceBinding()).isSameAs(fixture.source().request());
    assertThat(first.entries()).containsExactlyElementsOf(retry.entries());
    assertThat(first.entries())
        .extracting(Entry::identity)
        .containsExactlyElementsOf(fixture.rows().stream().map(Row::template).toList());
    assertThat(fixture.source().graphBytes()).isEqualTo(GRAPH_BYTES);
    assertThat(fixture.source().storageResultBytes()).isEqualTo(STORAGE_BYTES);
    assertThat(fixture.source().resultBytes()).isEqualTo(RESULT_BYTES);
    for (Entry entry : first.entries()) {
      String exactPayload =
          fixture.plan().binding().revisions().stream()
              .filter(
                  revision ->
                      revision
                          .revisionId()
                          .equals(
                              uuidFrom(entry.source().authoredRevision().getLogicalRevisionId())))
              .findFirst()
              .orElseThrow()
              .payload();
      assertThat(entry.source().inputPayload()).isEqualTo(exactPayload);
      assertThat(entry.source().inputPayloadBytes())
          .isEqualTo(exactPayload.getBytes(StandardCharsets.UTF_8));
    }

    var authoredRoom =
        first.rooms().stream()
            .filter(room -> room.identity().templateId().equals(ROOM_A1))
            .findFirst()
            .orElseThrow();
    String expectedLocalizedName = "{ \"en-US\" : \"Café 🐉\", \"ja\" : \"雪\" }";
    String expectedLocalizedDescription = "{\"en-US\":\"A quiet room\"}";
    assertThat(authoredRoom.content().getNameLocalizedVariantsJson())
        .isEqualTo(expectedLocalizedName);
    assertThat(authoredRoom.content().getDescriptionLocalizedVariantsJson())
        .isEqualTo(expectedLocalizedDescription);
    assertThat(authoredRoom.source().authoredRevision().getRoom().getNameLocalizedVariantsJson())
        .isEqualTo(expectedLocalizedName);
  }

  @Test
  void rejectsMissingDuplicateAndTypeConfusedFrozenRows() throws Exception {
    Fixture fixture = fixture();

    List<Row> missingRegion = new ArrayList<>(fixture.rows());
    missingRegion.removeIf(row -> row.template().equals(new Template(Family.REGION, REGION_B)));
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceTopologyPlan.create(
                    withRows(fixture, missingRegion).source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("omits or adds");

    List<Row> duplicated = new ArrayList<>(fixture.rows());
    duplicated.set(1, fixture.rows().getFirst());
    assertThatThrownBy(
            () -> WorldCanonicalInstanceTopologyPlan.create(withRows(fixture, duplicated).source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing, duplicated");

    List<Row> typeConfused = new ArrayList<>(fixture.rows());
    Row zone = findRow(typeConfused, Family.ZONE, ZONE_A1);
    int index = typeConfused.indexOf(zone);
    typeConfused.set(
        index,
        new Row(
            new Template(Family.REGION, ZONE_A1),
            zone.mappingKey(),
            zone.privateRowKey(),
            zone.authored(),
            zone.content()));
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceTopologyPlan.create(withRows(fixture, typeConfused).source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("confuses authored family");

    List<Row> missingRoomReference = new ArrayList<>(fixture.rows());
    Row exit = findRow(missingRoomReference, Family.ROOM_EXIT, EXIT_A);
    int exitIndex = missingRoomReference.indexOf(exit);
    var badExitContent =
        exit.content().toBuilder()
            .setRoomExit(exit.content().getRoomExit().toBuilder().setToRoomId(uuid(999).toString()))
            .build();
    missingRoomReference.set(
        exitIndex,
        new Row(
            exit.template(),
            exit.mappingKey(),
            exit.privateRowKey(),
            exit.authored(),
            badExitContent));
    assertThatThrownBy(
            () ->
                WorldCanonicalInstanceTopologyPlan.create(
                    withRows(fixture, missingRoomReference).source()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Frozen content differs");
  }

  @Test
  void sourceCommitPlanRejectsCrossRegionExitOutsideItsDeclaredSubtree() {
    List<WorldDesignMutationRevision> mutations = mutations();
    for (int index = 0; index < mutations.size(); index++) {
      var mutation = mutations.get(index);
      if (UUID.fromString(mutation.getAggregateId()).equals(EXIT_A)) {
        mutations.set(
            index,
            mutation.toBuilder()
                .setRoomExit(mutation.getRoomExit().toBuilder().setToRoomId(ROOM_B.toString()))
                .build());
      }
    }

    assertThatThrownBy(() -> createPlan(mutations))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside declared scope");
  }

  private static Fixture fixture() throws Exception {
    WorldDraftTopologyCommitPlan plan = createPlan(mutations());
    WorldCanonicalAuthoredGraph graph = graph(plan);
    var request = new WorldCanonicalFrozenTopology.Request(plan, freeze(plan));
    WorldCanonicalFrozenTopology source =
        new WorldCanonicalFrozenTopology(
            uuid(100),
            request,
            mock(WorldAuthoredVersionIdentityReceipt.class),
            graph,
            GRAPH_BYTES,
            STORAGE_BYTES,
            RESULT_BYTES);
    return new Fixture(source, plan, graph.rows());
  }

  private static Fixture withRows(Fixture original, List<Row> rows) {
    WorldCanonicalAuthoredGraph graph =
        new WorldCanonicalAuthoredGraph(TENANT, VERSION, 9001, 9002, rows);
    WorldCanonicalFrozenTopology source =
        new WorldCanonicalFrozenTopology(
            original.source().captureId(),
            original.source().request(),
            mock(WorldAuthoredVersionIdentityReceipt.class),
            graph,
            GRAPH_BYTES,
            STORAGE_BYTES,
            RESULT_BYTES);
    return new Fixture(source, original.plan(), rows);
  }

  private static WorldDraftTopologyCommitPlan createPlan(
      List<WorldDesignMutationRevision> mutations) throws InvalidProtocolBufferException {
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> affected = new ArrayList<>();
    for (int index = 0; index < mutations.size(); index++) {
      var mutation = mutations.get(index);
      UUID revisionId = UUID.fromString(mutation.getLogicalRevisionId());
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(index),
              revisionId,
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(mutation)));
      String aggregate =
          mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      String scope = mutation.getScopeType().name().replace("WORLD_DESIGN_SCOPE_TYPE_", "");
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              aggregate,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0"));
      affected.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              aggregate,
              mutation.getAggregateId(),
              scope,
              mutation.getScopeId(),
              "0"));
    }
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                TENANT, VERSION, 41, "gd-tenant-41", 51, "gd-tenant-41", "NEW_GAME_ROW"),
            uuid(80),
            COMMIT,
            "base-commit",
            revisions,
            affected);
    return WorldDraftTopologyCommitPlan.create(binding, ownerBinding());
  }

  private static OwnerBinding ownerBinding() {
    return new OwnerBinding(
        "firemud",
        TENANT,
        VERSION,
        uuid(81),
        41,
        uuid(82),
        uuid(83),
        "sha256:" + "a".repeat(64),
        uuid(84),
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64));
  }

  private static CaptureRequest freeze(WorldDraftTopologyCommitPlan plan) {
    var owner = plan.ownerBinding();
    List<OwnedAffectedTuple> affected =
        plan.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    String publicationRequestId = "publication-request";
    return new CaptureRequest(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.intakeRequestId(),
        uuid(85),
        publicationRequestId,
        "d".repeat(64),
        7,
        PublicationDigestRequestBinding.full(
                TENANT.toString(), Long.toString(owner.gameDesignVersionId()), publicationRequestId)
            .derivedWorkflowIdentity(),
        plan.binding().commitId().toString(),
        "e".repeat(64),
        3,
        affected);
  }

  private static WorldCanonicalAuthoredGraph graph(WorldDraftTopologyCommitPlan plan) {
    List<Row> rows = new ArrayList<>();
    long key = 1;
    for (var node : plan.graph().nodes()) {
      Family family = family(node.mutation().getAggregateType());
      rows.add(
          new Row(
              new Template(family, node.templateId()),
              key,
              key + 1000,
              node,
              storageContent(node.mutation())));
      key++;
    }
    return new WorldCanonicalAuthoredGraph(TENANT, VERSION, 9001, 9002, rows);
  }

  private static WorldDesignMutationRevision storageContent(WorldDesignMutationRevision mutation) {
    return switch (mutation.getAggregateType()) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION ->
          mutation.getRegion().getSpacingMultiplier() == 0
              ? mutation.toBuilder()
                  .setRegion(mutation.getRegion().toBuilder().setSpacingMultiplier(1.0))
                  .build()
              : mutation;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT ->
          mutation.getRoomExit().getCost() == 0
              ? mutation.toBuilder()
                  .setRoomExit(mutation.getRoomExit().toBuilder().setCost(1))
                  .build()
              : mutation;
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING ->
          mutation.getWorldEntitySpawnBinding().getSpawnCount() == 0
              ? mutation.toBuilder()
                  .setWorldEntitySpawnBinding(
                      mutation.getWorldEntitySpawnBinding().toBuilder().setSpawnCount(1))
                  .build()
              : mutation;
      default -> mutation;
    };
  }

  private static List<WorldDesignMutationRevision> mutations() {
    String localizedName = "{ \"en-US\" : \"Café 🐉\", \"ja\" : \"雪\" }";
    String localizedDescription = "{\"en-US\":\"A quiet room\"}";
    List<WorldDesignMutationRevision> rows = new ArrayList<>();
    rows.add(
        base(
                1,
                REGION_A,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setRegion(
                RegionDesignMutation.newBuilder()
                    .setName("Northern Reach")
                    .setWeather("snow")
                    .setGeneratorParams("{ \"seed\" : 17 }")
                    .setSpacingMultiplier(0))
            .build());
    rows.add(
        base(
                2,
                ZONE_A1,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setZone(
                ZoneDesignMutation.newBuilder().setName("Harbor").setRegionId(REGION_A.toString()))
            .build());
    rows.add(
        base(
                3,
                ROOM_A1,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setRoom(
                RoomDesignMutation.newBuilder()
                    .setName("Quay")
                    .setDescription("Stone steps descend to the water.")
                    .setZoneId(ZONE_A1.toString())
                    .setNameLocalizedVariantsJson(localizedName)
                    .setDescriptionLocalizedVariantsJson(localizedDescription))
            .build());
    rows.add(
        base(
                4,
                REGION_B,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_B)
            .setRegion(
                RegionDesignMutation.newBuilder().setName("Southern Reach").setWeather("clear"))
            .build());
    rows.add(
        base(
                5,
                ZONE_B,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_B)
            .setZone(
                ZoneDesignMutation.newBuilder().setName("Lowland").setRegionId(REGION_B.toString()))
            .build());
    rows.add(
        base(
                6,
                ROOM_B,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_B)
            .setRoom(RoomDesignMutation.newBuilder().setName("Ford").setZoneId(ZONE_B.toString()))
            .build());
    rows.add(
        base(
                7,
                ZONE_A2,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setZone(
                ZoneDesignMutation.newBuilder()
                    .setName("Cliffside")
                    .setRegionId(REGION_A.toString()))
            .build());
    rows.add(
        base(
                8,
                ROOM_A2,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setRoom(
                RoomDesignMutation.newBuilder().setName("Beacon").setZoneId(ZONE_A2.toString()))
            .build());
    rows.add(
        base(
                9,
                EXIT_A,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setRoomExit(
                RoomExitDesignMutation.newBuilder()
                    .setFromRoomId(ROOM_A1.toString())
                    .setToRoomId(ROOM_A2.toString())
                    .setDirection("east")
                    .setCost(0))
            .build());
    rows.add(
        base(
                10,
                RULE_A,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setGenerationRule(
                GenerationRuleDesignMutation.newBuilder()
                    .setName("weather-profile")
                    .setValue("{ \"locale\" : \"Café\" }"))
            .build());
    rows.add(
        base(
                11,
                RULE_B,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE,
                ZONE_B)
            .setGenerationRule(
                GenerationRuleDesignMutation.newBuilder()
                    .setName("room-density")
                    .setValue("sparse"))
            .build());
    rows.add(
        base(
                12,
                SPAWN_A,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE,
                REGION_A)
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(ROOM_A1.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM)
                    .setEntityTemplateId(ENTITY_ITEM.toString())
                    .setSpawnCount(0)
                    .setRespawnDelaySeconds(300))
            .build());
    rows.add(
        base(
                13,
                SPAWN_B,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING,
                WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE,
                ZONE_B)
            .setWorldEntitySpawnBinding(
                WorldEntitySpawnBindingDesignMutation.newBuilder()
                    .setRoomId(ROOM_B.toString())
                    .setEntityTemplateType(
                        EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                    .setEntityTemplateId(ENTITY_NPC.toString())
                    .setSpawnCount(4)
                    .setRespawnDelaySeconds(60))
            .build());
    return rows;
  }

  private static WorldDesignMutationRevision.Builder base(
      int revision,
      UUID identity,
      WorldDesignAggregateType aggregate,
      WorldDesignScopeType scopeType,
      UUID scopeId) {
    return WorldDesignMutationRevision.newBuilder()
        .setLogicalRevisionId(uuid(200 + revision).toString())
        .setCommitId(COMMIT.toString())
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setAggregateType(aggregate)
        .setAggregateId(identity.toString())
        .setScopeType(scopeType)
        .setScopeId(scopeId.toString());
  }

  private static Row findRow(List<Row> rows, Family family, UUID identity) {
    return rows.stream()
        .filter(row -> row.template().equals(new Template(family, identity)))
        .findFirst()
        .orElseThrow();
  }

  private static Family family(WorldDesignAggregateType aggregate) {
    return switch (aggregate) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> Family.REGION;
      case WORLD_DESIGN_AGGREGATE_TYPE_ZONE -> Family.ZONE;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM -> Family.ROOM;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> Family.ROOM_EXIT;
      case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> Family.GENERATION_RULE;
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING ->
          Family.WORLD_ENTITY_SPAWN_BINDING;
      default -> throw new IllegalArgumentException("Unexpected test family");
    };
  }

  private static UUID uuidFrom(String value) {
    return UUID.fromString(value);
  }

  private static UUID uuid(int value) {
    return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(value));
  }

  private record Fixture(
      WorldCanonicalFrozenTopology source, WorldDraftTopologyCommitPlan plan, List<Row> rows) {}
}
