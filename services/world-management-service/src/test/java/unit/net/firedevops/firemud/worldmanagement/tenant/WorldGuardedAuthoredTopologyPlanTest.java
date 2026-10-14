package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.util.JsonFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import org.junit.jupiter.api.Test;

/** Typed component input only; these values supply no authenticated owner authority. */
class WorldGuardedAuthoredTopologyPlanTest {
  private final UUID tenant = UUID.randomUUID();
  private final UUID version = UUID.randomUUID();
  private final UUID commit = UUID.randomUUID();
  private final UUID region = UUID.randomUUID();
  private final OwnerBinding owner =
      new OwnerBinding(
          "firemud",
          tenant,
          version,
          UUID.randomUUID(),
          42L,
          UUID.randomUUID(),
          UUID.randomUUID(),
          "sha256:" + "1".repeat(64),
          UUID.randomUUID(),
          "sha256:" + "2".repeat(64),
          "sha256:" + "3".repeat(64));

  @Test
  void retainsOriginalEnclosingBytesAndDerivesExactScope() throws Exception {
    var binding = binding(json(mutation()), true);
    var plan = WorldDraftTopologyCommitPlan.create(binding, owner);
    assertThat(plan.binding().canonicalBytes()).isEqualTo(binding.canonicalBytes());
    assertThat(plan.graph().nodes()).hasSize(1);
    assertThat(plan.graph().nodes().getFirst().scopeId()).isEqualTo(region);
    assertThat(WorldDraftTopologyInputGraph.STATUS).isEqualTo("PURE_UNVERIFIED");
  }

  @Test
  void refusesMissingAffectedScope() throws Exception {
    var binding = binding(json(mutation()), false);
    assertThatThrownBy(() -> WorldDraftTopologyCommitPlan.create(binding, owner))
        .hasMessageContaining("affected units");
  }

  @Test
  void refusesUnknownPayloadFieldsAndDuplicateJsonKeys() throws Exception {
    String payload = json(mutation());
    var unknown =
        binding(payload.substring(0, payload.length() - 1) + ",\"unexpected\":true}", true);
    assertThatThrownBy(() -> WorldDraftTopologyCommitPlan.create(unknown, owner))
        .isInstanceOf(IllegalArgumentException.class);
    var duplicate =
        binding(
            payload.substring(0, payload.length() - 1) + ",\"commitId\":\"" + commit + "\"}", true);
    assertThatThrownBy(() -> WorldDraftTopologyCommitPlan.create(duplicate, owner))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesPayloadCommitSubstitutionAndChangedScope() throws Exception {
    var changedCommit =
        binding(
            json(mutation().toBuilder().setCommitId(UUID.randomUUID().toString()).build()), true);
    assertThatThrownBy(() -> WorldDraftTopologyCommitPlan.create(changedCommit, owner))
        .hasMessageContaining("commit/revision identity");
    var changedScope =
        binding(
            json(mutation().toBuilder().setScopeId(UUID.randomUUID().toString()).build()), true);
    assertThatThrownBy(() -> WorldDraftTopologyCommitPlan.create(changedScope, owner))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private WorldDesignMutationRevision mutation() {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(region.toString())
        .setAggregateId(region.toString())
        .setAggregateType(WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(region.toString())
        .setRegion(RegionDesignMutation.newBuilder().setName("Strict region"))
        .build();
  }

  private DraftCommitBinding binding(String payload, boolean completeScope) {
    var aggregate =
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            region.toString(),
            "AGGREGATE",
            region.toString(),
            "0");
    var scope =
        new AffectedUnit(
            Owner.WORLD_MANAGEMENT,
            "REGION",
            region.toString(),
            "REGION_SUBTREE",
            region.toString(),
            "0");
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            tenant, version, 42L, "source-key", 77L, "source-key", "NEW_GAME_ROW"),
        UUID.randomUUID(),
        commit,
        "component-base",
        List.of(new RevisionPayload("0", region, Owner.WORLD_MANAGEMENT, payload)),
        completeScope ? List.of(aggregate, scope) : List.of(aggregate));
  }

  private static String json(WorldDesignMutationRevision mutation) throws Exception {
    return JsonFormat.printer().omittingInsignificantWhitespace().print(mutation);
  }
}
