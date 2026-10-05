package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import org.junit.jupiter.api.Test;

class WorldCanonicalFrozenTopologyTest {
  @Test
  void exactFreezeRetainsSyntheticCheckpointWithoutRelabelingItsDigest() throws Exception {
    var plan = WorldCanonicalAuthoredGraphReaderTest.plan();
    var request = new Request(plan, freeze(plan, plan.binding().commitId().toString(), true));
    assertThat(request.freeze().contentDigest()).isEqualTo("b".repeat(64));
    assertThat(request.plan().binding()).isSameAs(plan.binding());
    assertThat(WorldCanonicalFrozenTopology.STATUS).isEqualTo("CAPTURED_UNVERIFIED");
  }

  @Test
  void rejectsSyntheticAppliedIdentityAndOmittedOriginalAffectedSet() throws Exception {
    var plan = WorldCanonicalAuthoredGraphReaderTest.plan();
    assertThatThrownBy(() -> new Request(plan, freeze(plan, "synthetic-commit", true)))
        .hasMessageContaining("exact selected complete commit");
    assertThatThrownBy(
            () -> new Request(plan, freeze(plan, plan.binding().commitId().toString(), false)))
        .hasMessageContaining("every exact original World affected tuple");
  }

  private CaptureRequest freeze(
      WorldDraftTopologyCommitPlan plan, String selectedCommit, boolean tuples) {
    var owner = plan.ownerBinding();
    var affected =
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
    return new CaptureRequest(
        owner.targetNamespace(),
        owner.canonicalTenantId(),
        owner.canonicalVersionId(),
        owner.intakeRequestId(),
        UUID.randomUUID(),
        "synthetic-capture",
        "a".repeat(64),
        1,
        "publish:" + owner.canonicalTenantId() + ":publish-request:synthetic-capture",
        selectedCommit,
        "b".repeat(64),
        2,
        tuples ? affected : List.of());
  }
}
