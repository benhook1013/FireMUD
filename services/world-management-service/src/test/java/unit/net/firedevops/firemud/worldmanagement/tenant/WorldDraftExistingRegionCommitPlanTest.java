package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import org.junit.jupiter.api.Test;

class WorldDraftExistingRegionCommitPlanTest {
  private static final UUID TENANT = id(1);
  private static final UUID VERSION = id(2);
  private static final UUID REGION = id(3);
  private static final UUID COMMIT = id(4);
  private static final UUID REVISION = id(5);
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void preservesOriginalMixedOwnerBindingAndUuidPropertyPayloadWithoutGrantingApplication() {
    var original = binding(json(mutation()), units());
    var owner = owner();
    var plan = WorldDraftExistingRegionCommitPlan.create(original, owner);
    assertThat(plan.binding()).isSameAs(original);
    assertThat(plan.ownerBinding()).isSameAs(owner);
    assertThat(plan.regionTemplateId()).isEqualTo(REGION);
    assertThat(plan.mutation()).isEqualTo(mutation());
    plan.mutation().toBuilder().setAggregateId(id(99).toString()).build();
    assertThat(plan.mutation()).isEqualTo(mutation());
    assertThat(plan.binding().baseCommitId()).isEqualTo("exact-reviewed-base");
    assertThat(plan.binding().revisions().getFirst().payload()).isEqualTo("opaque-other-owner");
    assertThat(plan.mutation().getRegion().getSpacingMultiplier()).isZero();
    // A pure existing-region plan does not widen the fresh writer's accepted input.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorldDraftTopologyCommitPlan.create(original, owner));
  }

  @Test
  void rejectsNumericNoncanonicalAndDifferentSubtreeSelectors() {
    for (String bad : List.of("17", "1-1-1-1-1", new UUID(0, 0).toString())) {
      rejected(mutation().toBuilder().setAggregateId(bad).setScopeId(bad).build());
    }
    rejected(mutation().toBuilder().setScopeId(id(99).toString()).build());
  }

  @Test
  void rejectsWrongFamilyFreshDeclarationAndMissingRegionProperties() {
    rejected(
        mutation().toBuilder()
            .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
            .build());
    rejected(
        mutation().toBuilder()
            .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE)
            .build());
    rejected(mutation().toBuilder().clearRegion().build());
    rejected(
        mutation().toBuilder()
            .setFreshGraphDeclaration(WorldFreshGraphDeclaration.getDefaultInstance())
            .build());
  }

  @Test
  void rejectsChangedEnclosingCommitAndRevision() {
    rejected(mutation().toBuilder().setCommitId(id(99).toString()).build());
    rejected(mutation().toBuilder().setLogicalRevisionId(id(99).toString()).build());
  }

  @Test
  void rejectsIncompleteChangedAndAdditionalEpochVector() {
    var original = units();
    rejected(binding(json(mutation()), original.subList(0, 1)));
    var changed = new ArrayList<>(original);
    changed.set(
        1,
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            REGION.toString(),
            "REGION_SUBTREE",
            REGION.toString(),
            "8"));
    rejected(binding(json(mutation()), changed));
    changed = new ArrayList<>(original);
    changed.add(
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            id(99).toString(),
            "AGGREGATE",
            id(99).toString(),
            "0"));
    rejected(binding(json(mutation()), changed));
  }

  @Test
  void rejectsEpochOverflowAndInvalidPropertyLimits() {
    rejected(mutation().toBuilder().setExpectedDraftRevisionEpoch(Long.MAX_VALUE).build());
    rejected(mutation().toBuilder().setExpectedDraftScopeRevisionEpoch(-1).build());
    for (var bad :
        List.of(
            mutation().getRegion().toBuilder().setName(" ").build(),
            mutation().getRegion().toBuilder().setName("x".repeat(101)).build(),
            mutation().getRegion().toBuilder().setWeather("x".repeat(51)).build(),
            mutation().getRegion().toBuilder().setGeneratorType("x".repeat(51)).build(),
            mutation().getRegion().toBuilder().setSpacingMultiplier(-1).build(),
            mutation().getRegion().toBuilder().setShardId(-1).build())) {
      rejected(mutation().toBuilder().setRegion(bad).build());
    }
  }

  @Test
  void rejectsWrongTenantVersionAndSourceOwnerAssociation() {
    var original = binding(json(mutation()), units());
    for (var target :
        List.of(
            new DraftCommitBinding.TargetProof(
                id(99), VERSION, 901, "source", 801, "source", "NEW_GAME_ROW"),
            new DraftCommitBinding.TargetProof(
                TENANT, id(99), 901, "source", 801, "source", "NEW_GAME_ROW"),
            new DraftCommitBinding.TargetProof(
                TENANT, VERSION, 902, "source", 801, "source", "NEW_GAME_ROW"))) {
      rejected(
          DraftCommitBinding.create(
              target,
              original.requestId(),
              COMMIT,
              original.baseCommitId(),
              original.revisions(),
              original.affectedUnits()));
    }
  }

  @Test
  void rejectsMalformedDuplicateUnknownAndTrailingJson() {
    for (String bad :
        List.of(
            "{",
            "null",
            "[]",
            "{} {}",
            "{\"unknown\":true}",
            "{\"commitId\":\"one\",\"commitId\":\"two\"}")) {
      rejected(binding(bad, units()));
    }
  }

  @Test
  void rejectsMultipleWorldMutationsRatherThanGuessingUpdateOrder() {
    var original = binding(json(mutation()), units());
    var revisions = new ArrayList<>(original.revisions());
    revisions.add(
        new DraftCommitBinding.RevisionPayload(
            "2",
            id(99),
            Owner.WORLD_MANAGEMENT,
            json(mutation().toBuilder().setLogicalRevisionId(id(99).toString()).build())));
    rejected(
        DraftCommitBinding.create(
            original.target(),
            original.requestId(),
            COMMIT,
            original.baseCommitId(),
            revisions,
            original.affectedUnits()));
  }

  private static WorldDesignMutationRevision mutation() {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(COMMIT.toString())
        .setLogicalRevisionId(REVISION.toString())
        .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
        .setAggregateId(REGION.toString())
        .setScopeId(REGION.toString())
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setExpectedDraftRevisionEpoch(4)
        .setExpectedDraftScopeRevisionEpoch(7)
        .setRegion(
            RegionDesignMutation.newBuilder()
                .setName("Updated region")
                .setWeather("rain")
                .setGeneratorType("islands")
                .setSpacingMultiplier(0)
                .setShardId(2))
        .build();
  }

  private static List<AffectedUnit> units() {
    return List.of(
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            REGION.toString(),
            "AGGREGATE",
            REGION.toString(),
            "4"),
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            REGION.toString(),
            "REGION_SUBTREE",
            REGION.toString(),
            "7"));
  }

  private static DraftCommitBinding binding(String payload, List<AffectedUnit> units) {
    var completeUnits = new ArrayList<>(units);
    completeUnits.add(
        new AffectedUnit(
            Owner.GAME_LOGIC, "SCRIPT", "other-script", "AGGREGATE", "other-script", "0"));
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            TENANT, VERSION, 901, "source", 801, "source", "NEW_GAME_ROW"),
        id(6),
        COMMIT,
        "exact-reviewed-base",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", id(7), Owner.GAME_LOGIC, "opaque-other-owner"),
            new DraftCommitBinding.RevisionPayload("1", REVISION, Owner.WORLD_MANAGEMENT, payload)),
        completeUnits);
  }

  private static OwnerBinding owner() {
    return new OwnerBinding(
        "firemud", TENANT, VERSION, id(50), 901, id(51), id(52), DIGEST, id(53), DIGEST, DIGEST);
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().print(mutation);
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException(invalid);
    }
  }

  private static void rejected(WorldDesignMutationRevision mutation) {
    rejected(binding(json(mutation), units()));
  }

  private static void rejected(DraftCommitBinding binding) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorldDraftExistingRegionCommitPlan.create(binding, owner()));
  }

  private static UUID id(int value) {
    return UUID.fromString(String.format("%08d-0000-4000-8000-%012d", value, value));
  }
}
