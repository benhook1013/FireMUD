package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorldSelectedPublicationSelectorCaptureTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID FENCE = UUID.fromString("abababab-abab-4bab-8bab-abababababab");

  @Test
  void capturesExactFenceCheckpointAndTuplesFromRetainedApplicationAndAcknowledgement() {
    var fixture = WorldSelectedDraftPublicationFreezeRequestTest.fixture();
    var acknowledgement = acknowledgement(fixture, fixture.owner().intakeRequestId());
    var canonicalFrozenTopology = mock(WorldCanonicalFrozenTopologyService.class);
    var capture = new WorldSelectedPublicationSelectorCapture(NAMESPACE, canonicalFrozenTopology);

    capture.capture(fixture.application(), acknowledgement);

    ArgumentCaptor<WorldCanonicalFrozenTopology.Request> requestCaptor =
        ArgumentCaptor.forClass(WorldCanonicalFrozenTopology.Request.class);
    verify(canonicalFrozenTopology).capture(requestCaptor.capture());
    var request = requestCaptor.getValue();
    var frozen = request.freeze();
    assertThat(request.plan()).isSameAs(fixture.application().plan());
    assertThat(frozen.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(frozen.canonicalTenantId()).isEqualTo(fixture.request().canonicalTenantId());
    assertThat(frozen.canonicalVersionId()).isEqualTo(fixture.request().canonicalVersionId());
    assertThat(frozen.intakeRequestId()).isEqualTo(acknowledgement.intakeRequestId());
    assertThat(frozen.publicationFence()).isEqualTo(acknowledgement.publicationFence());
    assertThat(frozen.publicationRequestId()).isEqualTo(fixture.request().publicationRequestId());
    assertThat(frozen.requestDigest()).isEqualTo(fixture.request().requestDigest());
    assertThat(frozen.versionStateEpoch()).isEqualTo(acknowledgement.versionStateEpoch());
    assertThat(frozen.publishWorkflowId())
        .isEqualTo(
            PublicationDigestRequestBinding.full(
                    fixture.request().canonicalTenantId().toString(),
                    Long.toString(fixture.binding().target().gameDesignVersionRowId()),
                    fixture.request().publicationRequestId())
                .derivedWorkflowIdentity());
    assertThat(frozen.appliedCommitId()).isEqualTo(acknowledgement.appliedCommitId());
    assertThat(frozen.contentDigest()).isEqualTo(acknowledgement.contentDigest());
    assertThat(frozen.digestSchemaVersion()).isEqualTo(acknowledgement.digestSchemaVersion());
    var expectedTuples =
        fixture.binding().affectedUnits(Owner.WORLD_MANAGEMENT).stream()
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
    assertThat(frozen.suppliedOwnedAffectedTuples()).containsExactlyElementsOf(expectedTuples);
  }

  @Test
  void changedOwnerIntakeCorrelationIsRejectedBeforeCanonicalCapture() {
    var fixture = WorldSelectedDraftPublicationFreezeRequestTest.fixture();
    var acknowledgement =
        acknowledgement(fixture, UUID.fromString("cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd"));
    var canonicalFrozenTopology = mock(WorldCanonicalFrozenTopologyService.class);
    var capture = new WorldSelectedPublicationSelectorCapture(NAMESPACE, canonicalFrozenTopology);

    assertThatThrownBy(() -> capture.capture(fixture.application(), acknowledgement))
        .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class)
        .hasMessageContaining("retained selected APPLIED application");

    verifyNoInteractions(canonicalFrozenTopology);
  }

  private static WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement acknowledgement(
      WorldSelectedDraftPublicationFreezeRequestTest.Fixture fixture, UUID intakeRequestId) {
    return new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
        fixture.request(),
        intakeRequestId,
        fixture.request().expectedVersionStateEpoch(),
        FENCE,
        WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN,
        fixture.binding().commitId().toString(),
        "a".repeat(64),
        3);
  }
}
