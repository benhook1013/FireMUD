package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionCommitPlan.RegionRevision;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeMutationPolicy;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldGenerationSubtreeDesignMutation;
import org.junit.jupiter.api.Test;

class WorldDraftRegionCommitPlanTest {
  private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID COMMIT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID REQUEST_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final long GAME_DESIGN_VERSION_ID = 9_000_000_042L;
  private static final String PREFIXED_DIGEST = "sha256:" + "a".repeat(64);
  private static final String OTHER_OWNER_PAYLOAD = "not decoded by the World plan";

  @Test
  void acceptsOrderedMultiRegionSubsetAndPreservesMixedOwnerBinding() {
    UUID firstRevision = uuid(5);
    UUID secondRevision = uuid(6);
    DraftCommitBinding binding =
        binding(
            List.of(
                otherRevision("0"),
                worldRevision("1", firstRevision, regionJson(firstRevision, COMMIT_ID, "7", 2, 4)),
                worldRevision(
                    "2", secondRevision, regionJson(secondRevision, COMMIT_ID, "19", 8, 3))),
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2),
                worldUnit("7", "REGION_SUBTREE", "7", 4),
                worldUnit("19", "AGGREGATE", "19", 8),
                worldUnit("19", "REGION_SUBTREE", "19", 3),
                otherUnit()));

    WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding = ownerBinding();
    WorldDraftRegionCommitPlan plan = WorldDraftRegionCommitPlan.create(binding, ownerBinding);

    assertThat(plan.binding()).isSameAs(binding);
    assertThat(plan.ownerBinding()).isSameAs(ownerBinding);
    assertThat(plan.binding().requestId()).isEqualTo(REQUEST_ID);
    assertThat(plan.binding().baseCommitId()).isEqualTo("base-commit-17");
    assertThat(plan.regionRevisions())
        .extracting(RegionRevision::revisionOrder)
        .containsExactly("1", "2");
    assertThat(plan.regionRevisions())
        .extracting(revision -> revision.mutation().getAggregateId())
        .containsExactly("7", "19");
    assertThat(plan.regionRevisions().get(0).mutation().getExpectedDraftRevisionEpoch())
        .isEqualTo(2);
    assertThat(plan.regionRevisions().get(0).mutation().getExpectedDraftScopeRevisionEpoch())
        .isEqualTo(4);
    assertThat(plan.regionRevisions().get(1).mutation().getExpectedDraftRevisionEpoch())
        .isEqualTo(8);
    assertThat(plan.regionRevisions().get(1).mutation().getExpectedDraftScopeRevisionEpoch())
        .isEqualTo(3);
  }

  @Test
  void rejectsMissingContainingScopeAndAdditionalOrMismatchedWorldTuples() {
    UUID revisionId = uuid(5);
    String payload = regionJson(revisionId, COMMIT_ID, "7", 2, 4);
    List<DraftCommitBinding.RevisionPayload> revisions =
        List.of(worldRevision("0", revisionId, payload));

    assertPlanRejected(binding(revisions, List.of(worldUnit("7", "AGGREGATE", "7", 2))));
    assertPlanRejected(
        binding(
            revisions,
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2),
                worldUnit("7", "REGION_SUBTREE", "7", 4),
                worldUnit("7", "ZONE_SUBTREE", "7", 4))));
    assertPlanRejected(
        binding(
            revisions,
            List.of(
                worldUnit("7", "AGGREGATE", "7", 3), worldUnit("7", "REGION_SUBTREE", "7", 4))));
    assertPlanRejected(
        binding(
            revisions,
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 5))));
  }

  @Test
  void rejectsChangedTargetAndProtoCommitOrRevisionIdentity() {
    UUID revisionId = uuid(5);
    List<DraftCommitBinding.RevisionPayload> revisions =
        List.of(worldRevision("0", revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, 4)));
    List<AffectedUnit> units =
        List.of(worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4));
    DraftCommitBinding valid = binding(revisions, units);

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                WorldDraftRegionCommitPlan.create(
                    valid,
                    ownerBinding(
                        TENANT_ID,
                        UUID.fromString("55555555-5555-4555-8555-555555555555"),
                        GAME_DESIGN_VERSION_ID)));
    assertPlanRejected(
        binding(
            List.of(worldRevision("0", revisionId, regionJson(revisionId, uuid(9), "7", 2, 4))),
            units));
    assertPlanRejected(
        binding(
            List.of(worldRevision("0", revisionId, regionJson(uuid(10), COMMIT_ID, "7", 2, 4))),
            units));
  }

  @Test
  void comparesOnlyTheSharedCanonicalTargetAndGameDesignVersionSelector() {
    UUID revisionId = uuid(5);
    List<DraftCommitBinding.RevisionPayload> revisions =
        List.of(worldRevision("0", revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, 4)));
    List<AffectedUnit> units =
        List.of(worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4));

    DraftCommitBinding differentTenant =
        binding(targetProof(uuid(50), VERSION_ID, GAME_DESIGN_VERSION_ID), revisions, units);
    DraftCommitBinding differentGameDesignVersion =
        binding(targetProof(TENANT_ID, VERSION_ID, GAME_DESIGN_VERSION_ID + 1L), revisions, units);

    assertPlanRejected(differentTenant, ownerBinding());
    assertPlanRejected(differentGameDesignVersion, ownerBinding());
    assertPlanRejected(
        binding(revisions, units),
        ownerBinding(TENANT_ID, VERSION_ID, GAME_DESIGN_VERSION_ID + 1L));
  }

  @Test
  void rejectsUnknownPayloadFieldsAndMalformedTypedJson() {
    UUID revisionId = uuid(5);
    String valid = regionJson(revisionId, COMMIT_ID, "7", 2, 4);
    String withUnknownField = valid.substring(0, valid.length() - 1) + ",\"unrecognized\":true}";

    assertPlanRejected(
        binding(
            List.of(worldRevision("0", revisionId, withUnknownField)),
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4))));
    assertPlanRejected(
        binding(
            List.of(worldRevision("0", revisionId, "{")),
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4))));
  }

  @Test
  void rejectsNoncanonicalOrOutOfRangeRegionIdsAndMismatchedScopeIds() {
    UUID revisionId = uuid(5);
    for (String id : List.of("0", "01", "+7", " 7", "9223372036854775808")) {
      assertPlanRejected(
          binding(
              List.of(worldRevision("0", revisionId, regionJson(revisionId, COMMIT_ID, id, 2, 4))),
              List.of(
                  worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4))));
    }
    assertPlanRejected(
        binding(
            List.of(
                worldRevision("0", revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, 4, "8"))),
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4))));
  }

  @Test
  void rejectsNegativeMaxAndBindingMismatchedEpochs() {
    UUID revisionId = uuid(5);
    assertPlanRejected(
        planBinding(revisionId, regionJson(revisionId, COMMIT_ID, "7", -1, 4), 0, 4));
    assertPlanRejected(
        planBinding(
            revisionId,
            regionJson(revisionId, COMMIT_ID, "7", Long.MAX_VALUE, 4),
            Long.MAX_VALUE,
            4));
    assertPlanRejected(
        planBinding(revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, -1), 2, 0));
    assertPlanRejected(
        planBinding(
            revisionId,
            regionJson(revisionId, COMMIT_ID, "7", 2, Long.MAX_VALUE),
            2,
            Long.MAX_VALUE));
    assertPlanRejected(planBinding(revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, 4), 3, 4));

    WorldDraftRegionCommitPlan plan =
        WorldDraftRegionCommitPlan.create(
            planBinding(
                revisionId,
                regionJson(revisionId, COMMIT_ID, "7", Long.MAX_VALUE - 1, 4),
                Long.MAX_VALUE - 1,
                4),
            ownerBinding());
    assertThat(plan.regionRevisions().getFirst().mutation().getExpectedDraftRevisionEpoch())
        .isEqualTo(Long.MAX_VALUE - 1);
  }

  @Test
  void rejectsDuplicateRegionMutationsAndUnsupportedCreateDeleteReplacementOrGenerationShapes() {
    UUID firstRevision = uuid(5);
    UUID secondRevision = uuid(6);
    assertPlanRejected(
        binding(
            List.of(
                worldRevision("0", firstRevision, regionJson(firstRevision, COMMIT_ID, "7", 2, 4)),
                worldRevision(
                    "1", secondRevision, regionJson(secondRevision, COMMIT_ID, "7", 2, 4))),
            List.of(
                worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4))));
    assertPlanRejected(
        planBinding(firstRevision, regionJson(firstRevision, COMMIT_ID, "", 2, 4), 2, 4));
    assertPlanRejected(
        planBinding(
            firstRevision,
            json(
                regionRevision(firstRevision, COMMIT_ID, "7", 2, 4).toBuilder()
                    .setOperation(
                        WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_DELETE)
                    .build()),
            2,
            4));
    assertPlanRejected(
        planBinding(
            firstRevision,
            json(
                regionRevision(firstRevision, COMMIT_ID, "7", 2, 4).toBuilder()
                    .setOperation(
                        WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UNSPECIFIED)
                    .build()),
            2,
            4));
    assertPlanRejected(
        planBinding(
            firstRevision,
            json(
                regionRevision(firstRevision, COMMIT_ID, "7", 2, 4).toBuilder()
                    .setScopeMutationPolicy(
                        WorldDesignScopeMutationPolicy
                            .WORLD_DESIGN_SCOPE_MUTATION_POLICY_REPLACE_SCOPE)
                    .build()),
            2,
            4));
    assertPlanRejected(
        planBinding(
            firstRevision,
            json(
                regionRevision(firstRevision, COMMIT_ID, "7", 2, 4).toBuilder()
                    .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_NEW_EMPTY_REGION)
                    .build()),
            2,
            4));
    assertPlanRejected(
        planBinding(
            firstRevision,
            json(
                WorldDesignMutationRevision.newBuilder()
                    .setLogicalRevisionId(firstRevision.toString())
                    .setCommitId(COMMIT_ID.toString())
                    .setOperation(
                        WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
                    .setAggregateType(
                        WorldDesignAggregateType
                            .WORLD_DESIGN_AGGREGATE_TYPE_WORLD_GENERATION_SUBTREE)
                    .setAggregateId("7")
                    .setExpectedDraftRevisionEpoch(2)
                    .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
                    .setScopeId("7")
                    .setExpectedDraftScopeRevisionEpoch(4)
                    .setWorldGenerationSubtree(
                        WorldGenerationSubtreeDesignMutation.getDefaultInstance())
                    .build()),
            2,
            4));
  }

  @Test
  void rejectsBlankNameNonfiniteOrNegativeSpacingAndNonRegionPayload() {
    UUID revisionId = uuid(5);
    for (double spacing : List.of(Double.NaN, Double.POSITIVE_INFINITY, -0.5d)) {
      assertPlanRejected(
          planBinding(
              revisionId,
              json(
                  regionRevision(revisionId, COMMIT_ID, "7", 2, 4).toBuilder()
                      .setRegion(
                          RegionDesignMutation.newBuilder()
                              .setName("North")
                              .setSpacingMultiplier(spacing))
                      .build()),
              2,
              4));
    }
    assertPlanRejected(
        planBinding(
            revisionId,
            json(
                regionRevision(revisionId, COMMIT_ID, "7", 2, 4).toBuilder()
                    .setRegion(
                        RegionDesignMutation.newBuilder().setName("   ").setSpacingMultiplier(1.0d))
                    .build()),
            2,
            4));
    assertPlanRejected(
        planBinding(
            revisionId,
            json(
                regionRevision(revisionId, COMMIT_ID, "7", 2, 4).toBuilder().clearRegion().build()),
            2,
            4));
  }

  @Test
  void preservesZeroSpacingForTheExistingWorldDefaultRule() {
    UUID revisionId = uuid(5);
    String payload =
        json(
            regionRevision(revisionId, COMMIT_ID, "7", 2, 4).toBuilder()
                .setRegion(RegionDesignMutation.newBuilder().setName("Northern Reach"))
                .build());
    DraftCommitBinding binding =
        binding(
            List.of(worldRevision("0", revisionId, payload)),
            List.of(worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4)));

    WorldDraftRegionCommitPlan plan = WorldDraftRegionCommitPlan.create(binding, ownerBinding());

    assertThat(plan.regionRevisions().getFirst().mutation().getRegion().getSpacingMultiplier())
        .isZero();
    assertThat(plan.binding().revisions().getFirst().payload()).isEqualTo(payload);
  }

  @Test
  void sharedBindingRejectsDuplicateAffectedWorldTuples() {
    UUID revisionId = uuid(5);
    List<AffectedUnit> units =
        List.of(
            worldUnit("7", "AGGREGATE", "7", 2),
            worldUnit("7", "AGGREGATE", "7", 2),
            worldUnit("7", "REGION_SUBTREE", "7", 4));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                binding(
                    List.of(
                        worldRevision(
                            "0", revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, 4))),
                    units));
  }

  @Test
  void planCollectionsAreDefensiveAndKeepTheExactSharedBaseAndRequest() {
    UUID revisionId = uuid(5);
    ArrayList<DraftCommitBinding.RevisionPayload> supplied =
        new ArrayList<>(
            List.of(worldRevision("0", revisionId, regionJson(revisionId, COMMIT_ID, "7", 2, 4))));
    DraftCommitBinding binding =
        binding(
            supplied,
            List.of(worldUnit("7", "AGGREGATE", "7", 2), worldUnit("7", "REGION_SUBTREE", "7", 4)));
    supplied.clear();
    WorldDraftRegionCommitPlan plan = WorldDraftRegionCommitPlan.create(binding, ownerBinding());

    assertThat(plan.binding().requestId()).isEqualTo(REQUEST_ID);
    assertThat(plan.binding().baseCommitId()).isEqualTo("base-commit-17");
    assertThat(plan.regionRevisions()).hasSize(1);
    assertThatThrownBy(() -> plan.regionRevisions().add(plan.regionRevisions().getFirst()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static DraftCommitBinding planBinding(
      UUID revisionId, String payload, long aggregateEpoch, long scopeEpoch) {
    return binding(
        List.of(worldRevision("0", revisionId, payload)),
        List.of(
            worldUnit("7", "AGGREGATE", "7", aggregateEpoch),
            worldUnit("7", "REGION_SUBTREE", "7", scopeEpoch)));
  }

  private static DraftCommitBinding binding(
      List<DraftCommitBinding.RevisionPayload> revisions, List<AffectedUnit> units) {
    return binding(targetProof(TENANT_ID, VERSION_ID, GAME_DESIGN_VERSION_ID), revisions, units);
  }

  private static DraftCommitBinding binding(
      DraftCommitBinding.TargetProof target,
      List<DraftCommitBinding.RevisionPayload> revisions,
      List<AffectedUnit> units) {
    return DraftCommitBinding.create(
        target, REQUEST_ID, COMMIT_ID, "base-commit-17", revisions, units);
  }

  private static DraftCommitBinding.TargetProof targetProof(
      UUID tenantId, UUID versionId, long gameDesignVersionRowId) {
    return new DraftCommitBinding.TargetProof(
        tenantId,
        versionId,
        gameDesignVersionRowId,
        "tenant-123",
        1001L,
        "tenant-123",
        "NEW_GAME_ROW");
  }

  private static DraftCommitBinding.RevisionPayload worldRevision(
      String order, UUID revisionId, String payload) {
    return new DraftCommitBinding.RevisionPayload(
        order, revisionId, Owner.WORLD_MANAGEMENT, payload);
  }

  private static DraftCommitBinding.RevisionPayload otherRevision(String order) {
    return new DraftCommitBinding.RevisionPayload(
        order, uuid(30), Owner.GAME_LOGIC, OTHER_OWNER_PAYLOAD);
  }

  private static AffectedUnit worldUnit(
      String aggregateId, String scopeType, String scopeId, long expectedEpoch) {
    return new AffectedUnit(
        Owner.WORLD_MANAGEMENT,
        "REGION",
        aggregateId,
        scopeType,
        scopeId,
        Long.toString(expectedEpoch));
  }

  private static AffectedUnit otherUnit() {
    return new AffectedUnit(
        Owner.GAME_LOGIC, "ABILITY", "ability-77", "AGGREGATE", "ability-77", "0");
  }

  private static WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding() {
    return ownerBinding(TENANT_ID, VERSION_ID, GAME_DESIGN_VERSION_ID);
  }

  private static WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding(
      UUID tenantId, UUID versionId, long gameDesignVersionId) {
    return new WorldDesignPublicationFenceEvidence.OwnerBinding(
        "firemud",
        tenantId,
        versionId,
        uuid(40),
        gameDesignVersionId,
        uuid(41),
        uuid(42),
        PREFIXED_DIGEST,
        uuid(43),
        PREFIXED_DIGEST,
        PREFIXED_DIGEST);
  }

  private static String regionJson(
      UUID revisionId, UUID commitId, String regionId, long aggregateEpoch, long scopeEpoch) {
    return regionJson(revisionId, commitId, regionId, aggregateEpoch, scopeEpoch, regionId);
  }

  private static String regionJson(
      UUID revisionId,
      UUID commitId,
      String regionId,
      long aggregateEpoch,
      long scopeEpoch,
      String scopeId) {
    return json(
        regionRevision(revisionId, commitId, regionId, aggregateEpoch, scopeEpoch, scopeId));
  }

  private static WorldDesignMutationRevision regionRevision(
      UUID revisionId, UUID commitId, String regionId, long aggregateEpoch, long scopeEpoch) {
    return regionRevision(revisionId, commitId, regionId, aggregateEpoch, scopeEpoch, regionId);
  }

  private static WorldDesignMutationRevision regionRevision(
      UUID revisionId,
      UUID commitId,
      String regionId,
      long aggregateEpoch,
      long scopeEpoch,
      String scopeId) {
    return WorldDesignMutationRevision.newBuilder()
        .setLogicalRevisionId(revisionId.toString())
        .setCommitId(commitId.toString())
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
        .setAggregateId(regionId)
        .setExpectedDraftRevisionEpoch(aggregateEpoch)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(scopeId)
        .setExpectedDraftScopeRevisionEpoch(scopeEpoch)
        .setRegion(
            RegionDesignMutation.newBuilder().setName("Northern Reach").setSpacingMultiplier(1.25d))
        .build();
  }

  private static String json(WorldDesignMutationRevision mutation) {
    try {
      return JsonFormat.printer().print(mutation);
    } catch (InvalidProtocolBufferException exception) {
      throw new IllegalStateException(
          "Test mutation could not be rendered as protobuf JSON", exception);
    }
  }

  private static void assertPlanRejected(DraftCommitBinding binding) {
    assertPlanRejected(binding, ownerBinding());
  }

  private static void assertPlanRejected(
      DraftCommitBinding binding, WorldDesignPublicationFenceEvidence.OwnerBinding ownerBinding) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WorldDraftRegionCommitPlan.create(binding, ownerBinding));
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-0000-4000-8000-%012d", value, value));
  }
}
