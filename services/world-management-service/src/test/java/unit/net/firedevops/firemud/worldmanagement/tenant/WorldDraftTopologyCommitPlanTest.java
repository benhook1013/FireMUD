package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyCommitPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph;
import net.firedevops.firemud.worldmanagement.v1.EntityTemplateReferenceType;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.WorldGenerationSubtreeDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.junit.jupiter.api.Test;

class WorldDraftTopologyCommitPlanTest {
  private static final UUID TENANT = id(1);
  private static final UUID VERSION = id(2);
  private static final UUID COMMIT = id(3);
  private static final UUID REGION = id(10);
  private static final UUID ZONE = id(11);
  private static final UUID ROOM = id(12);
  private static final UUID OTHER_ROOM = id(13);
  private static final UUID ENTITY = id(30);
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void preservesCompleteMixedOwnerBindingAllFamiliesAndEveryPayloadField() {
    List<WorldDesignMutationRevision> mutations = fresh();
    DraftCommitBinding binding = binding(mutations);
    OwnerBinding owner = owner();
    var plan = WorldDraftTopologyCommitPlan.create(binding, owner);

    assertThat(plan.binding()).isSameAs(binding);
    assertThat(plan.ownerBinding()).isSameAs(owner);
    assertThat(plan.graph().tenantId()).isEqualTo(TENANT);
    assertThat(plan.graph().versionId()).isEqualTo(VERSION);
    assertThat(WorldDraftTopologyInputGraph.STATUS).isEqualTo("PURE_UNVERIFIED");
    assertThat(plan.graph().nodes())
        .extracting(WorldDraftTopologyInputGraph.Node::mutation)
        .containsExactlyElementsOf(mutations);
    assertThat(plan.graph().nodes())
        .extracting(WorldDraftTopologyInputGraph.Node::revisionOrder)
        .containsExactly("1", "2", "3", "4", "5", "6", "7");
    assertThat(plan.graph().family(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
        .hasSize(2);
    assertThat(plan.graph().nodes().getFirst().mutation().getRegion().getSpacingMultiplier())
        .isZero();
    var reference = plan.graph().nodes().getLast().entityReference();
    assertThat(reference.kind())
        .isEqualTo(EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC);
    assertThat(reference.tenantId()).isEqualTo(TENANT);
    assertThat(reference.versionId()).isEqualTo(VERSION);
    assertThat(reference.templateId()).isEqualTo(ENTITY);
    assertThat(plan.binding().baseCommitId()).isEqualTo("reviewed-base");
    assertThat(plan.binding().revisions().getFirst().payload())
        .isEqualTo("other-owner-byte-string");
    assertThatThrownBy(() -> plan.graph().nodes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                plan.graph()
                    .family(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                    .clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void acceptsOutOfParentOrderWithoutChangingCanonicalRevisionOrder() {
    List<WorldDesignMutationRevision> mutations = fresh();
    List<WorldDesignMutationRevision> reversed = new ArrayList<>(mutations.reversed());
    var plan = WorldDraftTopologyCommitPlan.create(binding(reversed), owner());
    assertThat(plan.graph().nodes())
        .extracting(WorldDraftTopologyInputGraph.Node::mutation)
        .containsExactlyElementsOf(reversed);
  }

  @Test
  void preservesZeroExitCostAndSpawnCountForTheExistingOwnerDefaults() {
    var mutations = fresh();
    mutations.set(
        4,
        mutations.get(4).toBuilder()
            .setRoomExit(mutations.get(4).getRoomExit().toBuilder().setCost(0))
            .build());
    mutations.set(
        6,
        mutations.get(6).toBuilder()
            .setWorldEntitySpawnBinding(
                mutations.get(6).getWorldEntitySpawnBinding().toBuilder().setSpawnCount(0))
            .build());
    var original = binding(mutations);
    var plan = WorldDraftTopologyCommitPlan.create(original, owner());
    assertThat(plan.binding()).isSameAs(original);
    assertThat(plan.graph().nodes().get(4).mutation().getRoomExit().getCost()).isZero();
    assertThat(plan.graph().nodes().get(6).mutation().getWorldEntitySpawnBinding().getSpawnCount())
        .isZero();
    assertThat(plan.graph().nodes())
        .extracting(WorldDraftTopologyInputGraph.Node::mutation)
        .containsExactlyElementsOf(mutations);
    assertThat(plan.binding().canonicalBytes()).isEqualTo(original.canonicalBytes());
  }

  @Test
  void completeDeclarationPreservesTypedStartRoomAndExplicitZeroOptionalFamilies() {
    List<WorldDesignMutationRevision> mutations = new ArrayList<>(fresh().subList(0, 3));
    var plan = WorldDraftTopologyCommitPlan.create(binding(mutations), owner());
    var declaration = plan.graph().freshGraphDeclaration().orElseThrow();

    assertThat(declaration.tenantId()).isEqualTo(TENANT);
    assertThat(declaration.versionId()).isEqualTo(VERSION);
    assertThat(declaration.startLocation())
        .isEqualTo(new net.firedevops.firemud.common.world.RoomTemplateRef(TENANT, VERSION, ROOM));
    assertThat(declaration.familyCounts())
        .extracting(WorldDraftTopologyInputGraph.FamilyCount::count)
        .containsExactly(1, 1, 1, 0, 0, 0);
    assertThat(plan.graph().nodes())
        .extracting(node -> node.mutation().getAggregateType())
        .containsExactly(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM);
  }

  @Test
  void rejectsAbsentZeroCountDuplicateDeclarationAndChangedSelectorIdentity() {
    List<WorldDesignMutationRevision> mutations = new ArrayList<>(fresh().subList(0, 3));
    DraftCommitBinding binding = binding(mutations);
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setFamilyCounts(
                    3, declaration.getFamilyCounts(3).toBuilder().clearCount().build())));
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setFamilyCounts(
                    0, declaration.getFamilyCounts(0).toBuilder().setCount(2).build())));
    rejected(
        changeDeclaration(
            binding,
            declaration -> {
              var first = declaration.getFamilyCounts(0);
              var second = declaration.getFamilyCounts(1);
              return declaration.setFamilyCounts(0, second).setFamilyCounts(1, first);
            }));
    rejected(changeDeclaration(binding, declaration -> declaration.setTenantId(id(99).toString())));
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setStartLocation(
                    declaration.getStartLocation().toBuilder().setVersionId(id(99).toString()))));
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setStartLocation(
                    declaration.getStartLocation().toBuilder().setTenantId(id(99).toString()))));
    rejected(changeDeclaration(binding, WorldFreshGraphDeclaration.Builder::clearStartLocation));
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setStartLocation(
                    declaration.getStartLocation().toBuilder()
                        .setRoomTemplateId(id(99).toString()))));
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setStartLocation(
                    declaration.getStartLocation().toBuilder()
                        .setRoomTemplateId(ZONE.toString()))));
    rejected(
        changeDeclaration(
            binding,
            declaration ->
                declaration.setStartLocation(
                    declaration.getStartLocation().toBuilder()
                        .setRoomTemplateId("00000000-0000-0000-0000-000000000000"))));

    List<DraftCommitBinding.RevisionPayload> duplicate = new ArrayList<>(binding.revisions());
    var roomRevision = duplicate.get(3);
    try {
      var roomMutation = WorldDesignMutationRevision.newBuilder();
      JsonFormat.parser().merge(roomRevision.payload(), roomMutation);
      duplicate.set(
          3,
          new DraftCommitBinding.RevisionPayload(
              roomRevision.revisionOrder(),
              roomRevision.revisionId(),
              roomRevision.owner(),
              JsonFormat.printer()
                  .print(
                      roomMutation.setFreshGraphDeclaration(bindingDeclaration(binding)).build())));
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalStateException(exception);
    }
    rejected(copy(binding, duplicate));
  }

  @Test
  void validatesZoneSubtreeAcrossAllDependentFamiliesAndDeniesNoWorldInput() {
    var mutations = fresh();
    for (int index = 1; index < mutations.size(); index++) {
      mutations.set(
          index,
          mutations.get(index).toBuilder()
              .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE)
              .setScopeId(ZONE.toString())
              .build());
    }
    var graph = WorldDraftTopologyCommitPlan.create(binding(mutations), owner()).graph();
    assertThat(graph.nodes().subList(1, graph.nodes().size()))
        .extracting(WorldDraftTopologyInputGraph.Node::scopeId)
        .containsOnly(ZONE);
    rejected(binding(List.of()));
  }

  @Test
  void sameUuidInDifferentFamiliesResolvesOnlyThroughItsTypedParentReference() {
    var mutations = fresh();
    mutations.set(1, mutations.get(1).toBuilder().setAggregateId(REGION.toString()).build());
    for (int index : List.of(2, 3)) {
      mutations.set(
          index,
          mutations.get(index).toBuilder()
              .setRoom(mutations.get(index).getRoom().toBuilder().setZoneId(REGION.toString()))
              .build());
    }
    var graph = WorldDraftTopologyCommitPlan.create(binding(mutations), owner()).graph();
    assertThat(
            graph
                .family(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .getFirst()
                .templateId())
        .isEqualTo(REGION);
    assertThat(
            graph
                .family(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .getFirst()
                .templateId())
        .isEqualTo(REGION);
    mutations.remove(1);
    // The remaining REGION with that UUID cannot satisfy a typed ZONE parent reference.
    rejected(binding(mutations));
  }

  @Test
  void rejectsMissingAdditionalOrChangedDerivedScopeTuple() {
    var mutations = fresh();
    List<AffectedUnit> units = units(mutations);
    units.removeLast();
    rejected(binding(mutations, units));
    units = units(mutations);
    units.add(
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT, "ROOM", ROOM.toString(), "ZONE_SUBTREE", ZONE.toString(), "0"));
    rejected(binding(mutations, units));
    units = units(mutations);
    AffectedUnit prior = units.get(1);
    units.set(
        1,
        new AffectedUnit(
            prior.owner(),
            prior.aggregateType(),
            prior.aggregateId(),
            prior.scopeType(),
            prior.scopeId(),
            "1"));
    rejected(binding(mutations, units));
  }

  @Test
  void rejectsEveryNumericNilUppercaseShortenedAndWhitespaceReference() {
    for (String bad :
        List.of(
            "12",
            "00000000-0000-0000-0000-000000000000",
            "ABCDEFAB-0000-4000-8000-000000000001",
            "1-0-4000-8000-1")) {
      rejected(change(2, b -> b.setAggregateId(bad)));
      rejected(change(1, b -> b.setScopeId(bad)));
      rejected(change(1, b -> b.setZone(b.getZone().toBuilder().setRegionId(bad))));
      rejected(change(2, b -> b.setRoom(b.getRoom().toBuilder().setZoneId(bad))));
      rejected(change(4, b -> b.setRoomExit(b.getRoomExit().toBuilder().setFromRoomId(bad))));
      rejected(change(4, b -> b.setRoomExit(b.getRoomExit().toBuilder().setToRoomId(bad))));
      rejected(
          change(
              6,
              b ->
                  b.setWorldEntitySpawnBinding(
                      b.getWorldEntitySpawnBinding().toBuilder().setRoomId(bad))));
      rejected(
          change(
              6,
              b ->
                  b.setWorldEntitySpawnBinding(
                      b.getWorldEntitySpawnBinding().toBuilder().setEntityTemplateId(bad))));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() -> change(2, b -> b.setAggregateId(" " + ROOM)));
  }

  @Test
  void rejectsMissingAndWrongFamilyParentsOrScopeInsteadOfGuessingExistingRows() {
    rejected(change(1, b -> b.setZone(b.getZone().toBuilder().setRegionId(id(99).toString()))));
    rejected(change(2, b -> b.setRoom(b.getRoom().toBuilder().setZoneId(REGION.toString()))));
    rejected(
        change(4, b -> b.setRoomExit(b.getRoomExit().toBuilder().setToRoomId(id(99).toString()))));
    rejected(change(5, b -> b.setScopeId(ROOM.toString())));
    rejected(
        change(
            6,
            b ->
                b.setWorldEntitySpawnBinding(
                    b.getWorldEntitySpawnBinding().toBuilder().setRoomId(id(99).toString()))));
    List<WorldDesignMutationRevision> mutations = fresh();
    mutations.removeFirst();
    rejected(binding(mutations));
  }

  @Test
  void requiresBothExitEndpointsWithinDeclaredZoneButSupportsTheirCommonRegion() {
    List<WorldDesignMutationRevision> mutations = fresh();
    UUID secondZone = id(40);
    mutations.add(
        base(40, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setZone(
                ZoneDesignMutation.newBuilder()
                    .setName("Other zone")
                    .setRegionId(REGION.toString()))
            .build());
    mutations.set(
        3,
        mutations.get(3).toBuilder()
            .setRoom(mutations.get(3).getRoom().toBuilder().setZoneId(secondZone.toString()))
            .build());
    assertThat(WorldDraftTopologyCommitPlan.create(binding(mutations), owner()).graph().nodes())
        .hasSize(8);
    mutations.set(
        4,
        mutations.get(4).toBuilder()
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE)
            .setScopeId(ZONE.toString())
            .build());
    rejected(binding(mutations));
  }

  @Test
  void rejectsCrossRegionExitAndMismatchedAncestry() {
    List<WorldDesignMutationRevision> mutations = fresh();
    UUID otherRegion = id(41);
    UUID otherZone = id(42);
    mutations.add(
        base(41, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
            .setScopeId(otherRegion.toString())
            .setRegion(RegionDesignMutation.newBuilder().setName("South"))
            .build());
    mutations.add(
        base(42, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .setScopeId(otherRegion.toString())
            .setZone(
                ZoneDesignMutation.newBuilder()
                    .setName("South zone")
                    .setRegionId(otherRegion.toString()))
            .build());
    mutations.set(
        3,
        mutations.get(3).toBuilder()
            .setScopeId(otherRegion.toString())
            .setRoom(mutations.get(3).getRoom().toBuilder().setZoneId(otherZone.toString()))
            .build());
    rejected(binding(mutations));
    rejected(
        change(
            0,
            b ->
                b.setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE)
                    .setScopeId(ZONE.toString())));
    rejected(
        change(
            1,
            b ->
                b.setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE)
                    .setScopeId(ROOM.toString())));
  }

  @Test
  void rejectsDuplicateIdsRuleKeysSpawnKeysAndExitDirections() {
    List<WorldDesignMutationRevision> mutations = fresh();
    mutations.add(mutations.get(2).toBuilder().setLogicalRevisionId(id(500).toString()).build());
    rejected(binding(mutations));
    for (int index : List.of(4, 5, 6)) {
      mutations = fresh();
      mutations.add(
          mutations.get(index).toBuilder()
              .setAggregateId(id(60 + index).toString())
              .setLogicalRevisionId(id(600 + index).toString())
              .build());
      rejected(binding(mutations));
    }
    mutations = fresh();
    mutations.set(2, mutations.get(2).toBuilder().setAggregateId(ZONE.toString()).build());
    rejected(binding(mutations));
  }

  @Test
  void rejectsUnsupportedOperationsPoliciesFamiliesAndFreshNonzeroEpochs() {
    rejected(
        change(
            0,
            b ->
                b.setOperation(
                    WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_DELETE)));
    rejected(
        change(
            0, b -> b.setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_NEW_EMPTY_REGION)));
    rejected(
        change(
            0,
            b ->
                b.setScopeMutationPolicy(
                    WorldDesignScopeMutationPolicy
                        .WORLD_DESIGN_SCOPE_MUTATION_POLICY_REPLACE_SCOPE)));
    rejected(change(0, b -> b.setExpectedDraftRevisionEpoch(1)));
    rejected(change(0, b -> b.setExpectedDraftScopeRevisionEpoch(Long.MAX_VALUE)));
    rejected(change(0, b -> b.setExpectedDraftRevisionEpoch(-1)));
    rejected(
        change(
            0,
            b ->
                b.setAggregateType(
                        WorldDesignAggregateType
                            .WORLD_DESIGN_AGGREGATE_TYPE_WORLD_GENERATION_SUBTREE)
                    .setWorldGenerationSubtree(
                        WorldGenerationSubtreeDesignMutation.getDefaultInstance())));
    rejected(change(0, WorldDesignMutationRevision.Builder::clearRegion));
    rejected(
        change(
            0, b -> b.setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)));
  }

  @Test
  void rejectsInvalidRequiredPayloadRangesAndNonfiniteValues() {
    for (double value :
        List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
      rejected(change(0, b -> b.setRegion(b.getRegion().toBuilder().setSpacingMultiplier(value))));
    }
    rejected(change(0, b -> b.setRegion(b.getRegion().toBuilder().setShardId(-1))));
    rejected(change(0, b -> b.setRegion(b.getRegion().toBuilder().setName(" "))));
    rejected(change(0, b -> b.setRegion(b.getRegion().toBuilder().setWeather("x".repeat(51)))));
    rejected(
        change(0, b -> b.setRegion(b.getRegion().toBuilder().setGeneratorType("x".repeat(51)))));
    rejected(change(1, b -> b.setZone(b.getZone().toBuilder().setName("x".repeat(101)))));
    rejected(change(2, b -> b.setRoom(b.getRoom().toBuilder().setName(""))));
    rejected(change(4, b -> b.setRoomExit(b.getRoomExit().toBuilder().setCost(-1))));
    rejected(change(4, b -> b.setRoomExit(b.getRoomExit().toBuilder().setDirection(""))));
    rejected(
        change(4, b -> b.setRoomExit(b.getRoomExit().toBuilder().setDirection("x".repeat(33)))));
    rejected(change(5, b -> b.setGenerationRule(b.getGenerationRule().toBuilder().setName(""))));
    rejected(
        change(
            5,
            b -> b.setGenerationRule(b.getGenerationRule().toBuilder().setValue("x".repeat(256)))));
    rejected(
        change(
            6,
            b ->
                b.setWorldEntitySpawnBinding(
                    b.getWorldEntitySpawnBinding().toBuilder().setSpawnCount(-1))));
    rejected(
        change(
            6,
            b ->
                b.setWorldEntitySpawnBinding(
                    b.getWorldEntitySpawnBinding().toBuilder().setRespawnDelaySeconds(-1))));
    rejected(
        change(
            6,
            b ->
                b.setWorldEntitySpawnBinding(
                    b.getWorldEntitySpawnBinding().toBuilder()
                        .setEntityTemplateType(
                            EntityTemplateReferenceType
                                .ENTITY_TEMPLATE_REFERENCE_TYPE_UNSPECIFIED))));
  }

  @Test
  void rejectsMalformedDuplicateUnknownAndTrailingJsonWithoutDroppingFields() {
    for (String bad :
        List.of(
            "{",
            "null",
            "[]",
            " ",
            "{} {}",
            "{\"commitId\":\"one\",\"commitId\":\"two\"}",
            "{\"unknown\":true}")) {
      var good = binding(fresh());
      var revisions = new ArrayList<>(good.revisions());
      var prior = revisions.get(1);
      revisions.set(
          1,
          new DraftCommitBinding.RevisionPayload(
              prior.revisionOrder(), prior.revisionId(), prior.owner(), bad));
      rejected(
          DraftCommitBinding.create(
              good.target(),
              good.requestId(),
              good.commitId(),
              good.baseCommitId(),
              revisions,
              good.affectedUnits()));
    }
  }

  @Test
  void rejectsChangedTargetCommitRevisionAndRetainedSourceWhileKeepingOwnerEvidenceUnauthenticated()
      throws InvalidProtocolBufferException {
    rejected(change(0, b -> b.setCommitId(id(99).toString())));
    var good = binding(fresh());
    var changedIdentity = new ArrayList<>(good.revisions());
    var firstWorld = changedIdentity.get(1);
    changedIdentity.set(
        1,
        new DraftCommitBinding.RevisionPayload(
            firstWorld.revisionOrder(),
            firstWorld.revisionId(),
            firstWorld.owner(),
            JsonFormat.printer()
                .print(
                    fresh().getFirst().toBuilder()
                        .setLogicalRevisionId(id(99).toString())
                        .build())));
    rejected(
        DraftCommitBinding.create(
            good.target(),
            good.requestId(),
            good.commitId(),
            good.baseCommitId(),
            changedIdentity,
            good.affectedUnits()));
    var target = good.target();
    for (DraftCommitBinding.TargetProof changed :
        List.of(
            new DraftCommitBinding.TargetProof(
                id(99), VERSION, 901, "source-key", 801, "source-key", "NEW_GAME_ROW"),
            new DraftCommitBinding.TargetProof(
                TENANT, id(99), 901, "source-key", 801, "source-key", "NEW_GAME_ROW"),
            new DraftCommitBinding.TargetProof(
                TENANT, VERSION, 902, "source-key", 801, "source-key", "NEW_GAME_ROW"),
            new DraftCommitBinding.TargetProof(
                TENANT,
                VERSION,
                target.gameDesignVersionRowId(),
                "source-key",
                801,
                "source-key",
                "RETAINED_GAME_V29"))) {
      rejected(
          DraftCommitBinding.create(
              changed,
              good.requestId(),
              good.commitId(),
              good.baseCommitId(),
              good.revisions(),
              good.affectedUnits()));
    }
    var revisions = new ArrayList<>(good.revisions());
    var prior = revisions.get(2);
    revisions.set(
        2,
        new DraftCommitBinding.RevisionPayload(
            prior.revisionOrder(), revisions.get(1).revisionId(), prior.owner(), prior.payload()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                DraftCommitBinding.create(
                    good.target(),
                    good.requestId(),
                    good.commitId(),
                    good.baseCommitId(),
                    revisions,
                    good.affectedUnits()));
  }

  private static List<WorldDesignMutationRevision> fresh() {
    return new ArrayList<>(
        List.of(
            base(10, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .setRegion(
                    RegionDesignMutation.newBuilder()
                        .setName("North")
                        .setWeather("snow")
                        .setShardId(2)
                        .setGenerationSeed(Long.MIN_VALUE)
                        .setGeneratorType("Dungeon")
                        .setGeneratorParams("{\"seed\":1}"))
                .build(),
            base(11, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Cavern")
                        .setRegionId(REGION.toString()))
                .build(),
            base(12, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Hall")
                        .setDescription("Original text")
                        .setZoneId(ZONE.toString())
                        .setNameLocalizedVariantsJson("{\"fr\":\"Salle\"}")
                        .setDescriptionLocalizedVariantsJson("{\"fr\":\"Texte\"}"))
                .build(),
            base(13, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                .setRoom(RoomDesignMutation.newBuilder().setName("Gate").setZoneId(ZONE.toString()))
                .build(),
            base(14, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT)
                .setRoomExit(
                    RoomExitDesignMutation.newBuilder()
                        .setFromRoomId(ROOM.toString())
                        .setToRoomId(OTHER_ROOM.toString())
                        .setDirection("NORTH")
                        .setCost(2))
                .build(),
            base(15, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE)
                .setGenerationRule(
                    GenerationRuleDesignMutation.newBuilder().setName("density").setValue("sparse"))
                .build(),
            base(
                    16,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)
                .setWorldEntitySpawnBinding(
                    WorldEntitySpawnBindingDesignMutation.newBuilder()
                        .setRoomId(ROOM.toString())
                        .setEntityTemplateType(
                            EntityTemplateReferenceType.ENTITY_TEMPLATE_REFERENCE_TYPE_NPC)
                        .setEntityTemplateId(ENTITY.toString())
                        .setSpawnCount(3)
                        .setRespawnDelaySeconds(90))
                .build()));
  }

  private static WorldDesignMutationRevision.Builder base(
      int number, WorldDesignAggregateType kind) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(COMMIT.toString())
        .setLogicalRevisionId(id(100 + number).toString())
        .setAggregateId(id(number).toString())
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setAggregateType(kind)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(REGION.toString());
  }

  private static DraftCommitBinding change(
      int index, UnaryOperator<WorldDesignMutationRevision.Builder> change) {
    List<WorldDesignMutationRevision> mutations = fresh();
    mutations.set(index, change.apply(mutations.get(index).toBuilder()).build());
    return binding(mutations);
  }

  private static DraftCommitBinding binding(List<WorldDesignMutationRevision> mutations) {
    return binding(mutations, units(mutations));
  }

  private static DraftCommitBinding binding(
      List<WorldDesignMutationRevision> mutations, List<AffectedUnit> units) {
    if (!mutations.isEmpty()) {
      var first = mutations.getFirst();
      mutations.set(0, first.toBuilder().setFreshGraphDeclaration(declaration(mutations)).build());
    }
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "0", id(700), Owner.GAME_LOGIC, "other-owner-byte-string"));
    for (int index = 0; index < mutations.size(); index++) {
      var mutation = mutations.get(index);
      UUID revision = UUID.fromString(mutation.getLogicalRevisionId());
      try {
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                Integer.toString(index + 1),
                revision,
                Owner.WORLD_MANAGEMENT,
                JsonFormat.printer().print(mutation)));
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(exception);
      }
    }
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 901, "source-key", 801, "source-key", "NEW_GAME_ROW"),
        id(4),
        COMMIT,
        "reviewed-base",
        revisions,
        units);
  }

  private static WorldFreshGraphDeclaration declaration(
      List<WorldDesignMutationRevision> mutations) {
    return WorldFreshGraphDeclaration.newBuilder()
        .setTenantId(TENANT.toString())
        .setVersionId(VERSION.toString())
        .setStartLocation(
            net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                .setTenantId(TENANT.toString())
                .setVersionId(VERSION.toString())
                .setRoomTemplateId(ROOM.toString()))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT))
        .addFamilyCounts(
            count(mutations, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE))
        .addFamilyCounts(
            count(
                mutations,
                WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING))
        .build();
  }

  private static WorldFreshGraphFamilyCount count(
      List<WorldDesignMutationRevision> mutations, WorldDesignAggregateType family) {
    int count =
        (int) mutations.stream().filter(mutation -> mutation.getAggregateType() == family).count();
    return WorldFreshGraphFamilyCount.newBuilder().setFamily(family).setCount(count).build();
  }

  private static DraftCommitBinding changeDeclaration(
      DraftCommitBinding binding, UnaryOperator<WorldFreshGraphDeclaration.Builder> change) {
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>(binding.revisions());
    for (int index = 0; index < revisions.size(); index++) {
      var revision = revisions.get(index);
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        continue;
      }
      try {
        var mutation = WorldDesignMutationRevision.newBuilder();
        JsonFormat.parser().merge(revision.payload(), mutation);
        if (!mutation.hasFreshGraphDeclaration()) {
          continue;
        }
        revisions.set(
            index,
            new DraftCommitBinding.RevisionPayload(
                revision.revisionOrder(),
                revision.revisionId(),
                revision.owner(),
                JsonFormat.printer()
                    .print(
                        mutation
                            .setFreshGraphDeclaration(
                                change
                                    .apply(mutation.getFreshGraphDeclaration().toBuilder())
                                    .build())
                            .build())));
        return copy(binding, revisions);
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(exception);
      }
    }
    throw new IllegalArgumentException("Test binding has no fresh graph declaration");
  }

  private static WorldFreshGraphDeclaration bindingDeclaration(DraftCommitBinding binding) {
    for (var revision : binding.revisions()) {
      if (revision.owner() != Owner.WORLD_MANAGEMENT) {
        continue;
      }
      try {
        var mutation = WorldDesignMutationRevision.newBuilder();
        JsonFormat.parser().merge(revision.payload(), mutation);
        if (mutation.hasFreshGraphDeclaration()) {
          return mutation.getFreshGraphDeclaration();
        }
      } catch (InvalidProtocolBufferException exception) {
        throw new IllegalStateException(exception);
      }
    }
    throw new IllegalArgumentException("Test binding has no fresh graph declaration");
  }

  private static DraftCommitBinding copy(
      DraftCommitBinding original, List<DraftCommitBinding.RevisionPayload> revisions) {
    return DraftCommitBinding.create(
        original.target(),
        original.requestId(),
        original.commitId(),
        original.baseCommitId(),
        revisions,
        original.affectedUnits());
  }

  private static List<AffectedUnit> units(List<WorldDesignMutationRevision> mutations) {
    List<AffectedUnit> units = new ArrayList<>();
    units.add(new AffectedUnit(Owner.GAME_LOGIC, "ABILITY", "other", "AGGREGATE", "other", "0"));
    // Deduplicate duplicate-object inputs so the owner plan, rather than this fixture, rejects
    // them.
    for (var mutation : mutations) {
      String type = mutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      AffectedUnit aggregate =
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              type,
              mutation.getAggregateId(),
              "AGGREGATE",
              mutation.getAggregateId(),
              "0");
      AffectedUnit scope =
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              type,
              mutation.getAggregateId(),
              mutation.getScopeType().name().replace("WORLD_DESIGN_SCOPE_TYPE_", ""),
              mutation.getScopeId(),
              "0");
      if (!units.contains(aggregate)) {
        units.add(aggregate);
      }
      if (!units.contains(scope)) {
        units.add(scope);
      }
    }
    return units;
  }

  private static OwnerBinding owner() {
    return new OwnerBinding(
        "firemud", TENANT, VERSION, id(50), 901, id(51), id(52), DIGEST, id(53), DIGEST, DIGEST);
  }

  private static void rejected(DraftCommitBinding binding) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorldDraftTopologyCommitPlan.create(binding, owner()));
  }

  private static UUID id(int number) {
    return UUID.fromString(String.format("%08d-0000-4000-8000-%012d", number, number));
  }
}
