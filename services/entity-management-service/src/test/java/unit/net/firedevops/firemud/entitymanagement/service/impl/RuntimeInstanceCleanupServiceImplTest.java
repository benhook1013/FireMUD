package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.entitymanagement.repository.QuarantinedActorRetentionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RuntimeInstanceCleanupServiceImplTest {
  private static final Long TENANT_ID = 29L;
  private static final String GAME_INSTANCE_ID = "runtime-opaque";
  private static final String TERMINATION_REQUEST_ID = "termination-request";

  @Test
  void observedEmptyTargetReturnsZeroCountsWithoutDeletingAnyFamily() {
    Dependencies dependencies = new Dependencies();
    when(dependencies.retentionRepository.hasUnclassifiedOrQuarantinedRuntimeEvidence(
            TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(false);

    var result =
        dependencies
            .service()
            .cleanupRuntimeInstance(TENANT_ID, GAME_INSTANCE_ID, TERMINATION_REQUEST_ID);

    assertThat(result.deletedRoomGroundEntries()).isZero();
    assertThat(result.deletedItemStacks()).isZero();
    assertThat(result.deletedItemInstances()).isZero();
    assertThat(result.deletedContainerInstances()).isZero();
  }

  @Test
  void unclassifiedOrQuarantinedEvidenceDeniesCleanupWithoutMutation() {
    Dependencies dependencies = new Dependencies();
    when(dependencies.retentionRepository.hasUnclassifiedOrQuarantinedRuntimeEvidence(
            TENANT_ID, GAME_INSTANCE_ID))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                dependencies
                    .service()
                    .cleanupRuntimeInstance(TENANT_ID, GAME_INSTANCE_ID, TERMINATION_REQUEST_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ENTITY_UNCLASSIFIED_OR_QUARANTINED_EVIDENCE_BLOCKS_CLEANUP");
  }

  private static final class Dependencies {
    private final QuarantinedActorRetentionRepository retentionRepository =
        Mockito.mock(QuarantinedActorRetentionRepository.class);

    private RuntimeInstanceCleanupServiceImpl service() {
      return new RuntimeInstanceCleanupServiceImpl(retentionRepository);
    }
  }
}
