package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.junit.jupiter.api.Test;

class WorldDraftRegionCommitEvidenceTest {
  @Test
  void graphAndResultBytesAreDefensiveAndRemainExplicitlyUnverifiedPermission() {
    byte[] graph = {1, 2};
    byte[] result = {3, 4};
    DraftCommitBinding binding = mock(DraftCommitBinding.class);
    WorldDesignPublicationFenceEvidence.OwnerBinding owner =
        mock(WorldDesignPublicationFenceEvidence.OwnerBinding.class);
    WorldDraftRegionCommitEvidence evidence =
        new WorldDraftRegionCommitEvidence(binding, owner, graph, result);
    graph[0] = 9;
    result[0] = 9;
    evidence.graphBytes()[0] = 8;
    evidence.resultBytes()[0] = 8;
    assertThat(evidence.graphBytes()).containsExactly((byte) 1, (byte) 2);
    assertThat(evidence.resultBytes()).containsExactly((byte) 3, (byte) 4);
    assertThat(evidence.binding()).isSameAs(binding);
    assertThat(evidence.ownerBinding()).isSameAs(owner);
    assertThat(evidence.status()).isEqualTo("STORED_PERMISSION_UNVERIFIED");
  }

  @Test
  void incompleteEvidenceCannotBeReturnedAsSuccessfulComponentStorage() {
    assertThatThrownBy(
            () ->
                new WorldDraftRegionCommitEvidence(
                    mock(DraftCommitBinding.class),
                    mock(WorldDesignPublicationFenceEvidence.OwnerBinding.class),
                    new byte[0],
                    new byte[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
