package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraph;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionGraphStager;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;

class WorldDraftRegionGraphStagerTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID COMMIT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final long GAME_DESIGN_VERSION_ID = 9_000_000_042L;
  private static final String PREFIXED_DIGEST = "sha256:" + "a".repeat(64);
  private static final long LARGE_REGION_ID = 9_223_372_036_854_775_806L;

  @Test
  void stagesOrderedExistingRegionsAndPreservesBindingRowsAndUntouchedFamilies() {
    RegionSpec first =
        new RegionSpec(
            LARGE_REGION_ID,
            uuid(5),
            RegionDesignMutation.newBuilder()
                .setName("Updated North")
                .setWeather("rain")
                .setShardId(3)
                .setGenerationSeed(Long.MAX_VALUE)
                .setGeneratorType("cave")
                .setGeneratorParams("density=4")
                .setSpacingMultiplier(0.0d)
                .build(),
            Long.MAX_VALUE - 1L,
            Long.MAX_VALUE - 2L);
    RegionSpec second =
        new RegionSpec(
            19L,
            uuid(6),
            RegionDesignMutation.newBuilder()
                .setName("Updated South")
                .setWeather("  ")
                .setShardId(0)
                .setGenerationSeed(Long.MIN_VALUE)
                .setGeneratorType(" ")
                .setGeneratorParams("\t")
                .setSpacingMultiplier(2.5d)
                .build(),
            7,
            9);
    WorldDraftRegionCommitPlan plan = plan(first, second);
    WorldAuthoredGraph priorGraph =
        graph(
            List.of(
                region(99L, "Untouched", "clear", "old", "old params", 10L, 1.5d),
                region(LARGE_REGION_ID, "Old North", "snow", "old", "old params", 11L, 0.75d),
                region(19L, "Old South", "sun", "old", "old params", 12L, 3.0d)),
            List.of(row(101L)),
            List.of(row(201L)),
            List.of(row(301L)),
            List.of(row(401L)),
            List.of(row(501L)));

    WorldDraftRegionGraphStager.UnverifiedStagedContent staged =
        new WorldDraftRegionGraphStager().stage(plan, priorGraph);

    assertThat(staged.binding()).isSameAs(plan.binding());
    assertThat(staged.ownerBinding()).isSameAs(plan.ownerBinding());
    assertThat(staged.binding().baseCommitId())
        .isEqualTo("base-commit-from-an-older-disjoint-snapshot");
    assertThat(staged.binding().affectedUnits(Owner.WORLD_MANAGEMENT))
        .extracting(AffectedUnit::expectedEpoch)
        .contains("9223372036854775806", "9223372036854775805");
    assertThat(plan.regionRevisions())
        .extracting(revision -> revision.revisionOrder())
        .containsExactly("0", "2");
    assertThat(staged.graph().regions())
        .extracting(row -> row.get("id"))
        .containsExactly(99L, LARGE_REGION_ID, 19L);
    assertThat(staged.graph().regions().get(0)).containsEntry("name", "Untouched");
    assertThat(staged.graph().regions().get(1))
        .containsEntry("id", LARGE_REGION_ID)
        .containsEntry("shardId", 3)
        .containsEntry("name", "Updated North")
        .containsEntry("weather", "rain")
        .containsEntry("generationSeed", Long.MAX_VALUE)
        .containsEntry("generatorType", "cave")
        .containsEntry("generatorParams", "density=4")
        .containsEntry("spacingMultiplier", 1.0d);
    assertThat(staged.graph().regions().get(2))
        .containsEntry("id", 19L)
        .containsEntry("name", "Updated South")
        .containsEntry("weather", null)
        .containsEntry("generationSeed", Long.MIN_VALUE)
        .containsEntry("generatorType", null)
        .containsEntry("generatorParams", null)
        .containsEntry("spacingMultiplier", 2.5d);
    assertThat(staged.graph().zones()).containsExactlyElementsOf(priorGraph.zones());
    assertThat(staged.graph().rooms()).containsExactlyElementsOf(priorGraph.rooms());
    assertThat(staged.graph().roomExits()).containsExactlyElementsOf(priorGraph.roomExits());
    assertThat(staged.graph().generationRules())
        .containsExactlyElementsOf(priorGraph.generationRules());
    assertThat(staged.graph().spawnBindings())
        .containsExactlyElementsOf(priorGraph.spawnBindings());
    assertThat(priorGraph.regions().get(1)).containsEntry("name", "Old North");
  }

  @Test
  void rejectsUnsupportedMissingDuplicateInconsistentAndInvalidTargetsWithoutPartialOutput() {
    RegionSpec first = validSpec(LARGE_REGION_ID, uuid(5));
    RegionSpec missing = validSpec(19L, uuid(6));
    WorldDraftRegionCommitPlan twoRegionPlan = plan(first, missing);
    WorldAuthoredGraph missingSecondTarget =
        graph(List.of(region(LARGE_REGION_ID, "Old North", null, null, null, 12L, 1.0d)));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new WorldDraftRegionGraphStager().stage(twoRegionPlan, missingSecondTarget));
    assertThat(missingSecondTarget.regions()).hasSize(1);
    assertThat(missingSecondTarget.regions().getFirst()).containsEntry("name", "Old North");

    WorldDraftRegionCommitPlan oneRegionPlan = plan(first);
    WorldAuthoredGraph duplicateTargets =
        graph(
            List.of(
                region(LARGE_REGION_ID, "North A", null, null, null, 1L, 1.0d),
                region(LARGE_REGION_ID, "North B", null, null, null, 2L, 1.0d)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new WorldDraftRegionGraphStager().stage(oneRegionPlan, duplicateTargets));

    Map<String, Object> inconsistentRegion =
        region(LARGE_REGION_ID, "North", null, null, null, 1L, 1.0d);
    inconsistentRegion.put("id", (int) LARGE_REGION_ID);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDraftRegionGraphStager()
                    .stage(oneRegionPlan, graph(List.of(inconsistentRegion))));

    Map<String, Object> missingField = region(LARGE_REGION_ID, "North", null, null, null, 1L, 1.0d);
    missingField.remove("weather");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDraftRegionGraphStager()
                    .stage(oneRegionPlan, graph(List.of(missingField))));

    Map<String, Object> extraField = region(LARGE_REGION_ID, "North", null, null, null, 1L, 1.0d);
    extraField.put("unrecognized", "value");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDraftRegionGraphStager().stage(oneRegionPlan, graph(List.of(extraField))));

    RegionSpec negativeShard =
        new RegionSpec(
            LARGE_REGION_ID,
            uuid(7),
            RegionDesignMutation.newBuilder().setName("Bad shard").setShardId(-1).build(),
            1,
            1);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new WorldDraftRegionGraphStager()
                    .stage(
                        plan(negativeShard),
                        graph(
                            List.of(
                                region(LARGE_REGION_ID, "North", null, null, null, 1L, 1.0d)))));

    RegionSpec nonfiniteSpacing =
        new RegionSpec(
            LARGE_REGION_ID,
            uuid(8),
            RegionDesignMutation.newBuilder()
                .setName("Bad spacing")
                .setSpacingMultiplier(Double.NaN)
                .build(),
            1,
            1);
    assertThatIllegalArgumentException().isThrownBy(() -> plan(nonfiniteSpacing));

    WorldDesignMutationRevision unsupportedMutation =
        mutation(first, WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_DELETE);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> planWithMutations(List.of(unsupportedMutation), List.of(first)));
  }

  private static WorldDraftRegionCommitPlan plan(RegionSpec... specs) {
    List<WorldDesignMutationRevision> mutations = new ArrayList<>();
    for (RegionSpec spec : specs) {
      mutations.add(
          mutation(spec, WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT));
    }
    return planWithMutations(mutations, List.of(specs));
  }

  private static WorldDraftRegionCommitPlan planWithMutations(
      List<WorldDesignMutationRevision> worldMutations, List<RegionSpec> specs) {
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<AffectedUnit> units = new ArrayList<>();
    int revisionOrder = 0;
    for (int index = 0; index < worldMutations.size(); index++) {
      WorldDesignMutationRevision mutation = worldMutations.get(index);
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(revisionOrder++),
              UUID.fromString(mutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              json(mutation)));
      RegionSpec spec = specs.get(index);
      units.add(worldUnit(Long.toString(spec.regionId()), "AGGREGATE", spec.aggregateEpoch()));
      units.add(worldUnit(Long.toString(spec.regionId()), "REGION_SUBTREE", spec.scopeEpoch()));
      if (index == 0) {
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                Integer.toString(revisionOrder++),
                uuid(30),
                Owner.GAME_LOGIC,
                "opaque game logic revision"));
        units.add(
            new AffectedUnit(
                Owner.GAME_LOGIC, "ABILITY", "ability-17", "AGGREGATE", "ability-17", "2"));
      }
    }
    DraftCommitBinding binding =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                TENANT_ID,
                VERSION_ID,
                GAME_DESIGN_VERSION_ID,
                "tenant-123",
                1001L,
                "tenant-123",
                "NEW_GAME_ROW"),
            REQUEST_ID,
            COMMIT_ID,
            "base-commit-from-an-older-disjoint-snapshot",
            revisions,
            units);
    return WorldDraftRegionCommitPlan.create(binding, ownerBinding());
  }

  private static WorldDesignMutationRevision mutation(
      RegionSpec spec, WorldDesignMutationOperation operation) {
    return WorldDesignMutationRevision.newBuilder()
        .setLogicalRevisionId(spec.revisionId().toString())
        .setCommitId(COMMIT_ID.toString())
        .setOperation(operation)
        .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
        .setAggregateId(Long.toString(spec.regionId()))
        .setExpectedDraftRevisionEpoch(spec.aggregateEpoch())
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(Long.toString(spec.regionId()))
        .setExpectedDraftScopeRevisionEpoch(spec.scopeEpoch())
        .setRegion(spec.payload())
        .build();
  }

  private static RegionSpec validSpec(long id, UUID revisionId) {
    return new RegionSpec(
        id,
        revisionId,
        RegionDesignMutation.newBuilder().setName("Updated").setSpacingMultiplier(1.0d).build(),
        1,
        1);
  }

  private static WorldAuthoredGraph graph(List<Map<String, Object>> regions) {
    return graph(regions, List.of(), List.of(), List.of(), List.of(), List.of());
  }

  private static WorldAuthoredGraph graph(
      List<Map<String, Object>> regions,
      List<Map<String, Object>> zones,
      List<Map<String, Object>> rooms,
      List<Map<String, Object>> roomExits,
      List<Map<String, Object>> generationRules,
      List<Map<String, Object>> spawnBindings) {
    return new WorldAuthoredGraph(regions, zones, rooms, roomExits, generationRules, spawnBindings);
  }

  private static Map<String, Object> region(
      long id,
      String name,
      String weather,
      String generatorType,
      String generatorParams,
      long generationSeed,
      double spacingMultiplier) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("shardId", 0);
    row.put("name", name);
    row.put("weather", weather);
    row.put("generationSeed", generationSeed);
    row.put("generatorType", generatorType);
    row.put("generatorParams", generatorParams);
    row.put("spacingMultiplier", spacingMultiplier);
    return row;
  }

  private static Map<String, Object> row(long id) {
    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    return row;
  }

  private static AffectedUnit worldUnit(String id, String scopeType, long epoch) {
    return new AffectedUnit(
        Owner.WORLD_MANAGEMENT, "REGION", id, scopeType, id, Long.toString(epoch));
  }

  private static WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding() {
    return new WorldDesignPublicationFenceEvidence.OwnerBinding(
        "firemud",
        TENANT_ID,
        VERSION_ID,
        uuid(40),
        GAME_DESIGN_VERSION_ID,
        uuid(41),
        uuid(42),
        PREFIXED_DIGEST,
        uuid(43),
        PREFIXED_DIGEST,
        PREFIXED_DIGEST);
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalStateException(
          "Test World revision could not be rendered as JSON", exception);
    }
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-0000-4000-8000-%012d", value, value));
  }

  private record RegionSpec(
      long regionId,
      UUID revisionId,
      RegionDesignMutation payload,
      long aggregateEpoch,
      long scopeEpoch) {}
}
