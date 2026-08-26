package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class TickRemediationServiceImplTest {
  @AfterEach
  void clear() {
    SessionContext.clear();
  }

  @Test
  void getRuntimeOwnershipStatusReturnsCanonicalStatus() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getRuntimeOwnershipStatus(1L, "7", null))
        .thenReturn(
            GetRuntimeOwnershipStatusResponse.newBuilder()
                .setOwnership(
                    RuntimeOwnershipStatus.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionEpoch(3L)
                        .setExecutorFence("fence-3")
                        .setOwnerService("game-session-service")
                        .setOwnerInstanceId("gs-1")
                        .setPaused(false)
                        .setLastCommittedTickBatchId("tb-9")
                        .setUpdatedAtMs(Instant.parse("2026-04-20T00:00:00Z").toEpochMilli())
                        .setLastCommittedTickId(14L)
                        .setRegionId("region-7")
                        .setPendingGameplayCommandCount(3L)
                        .setDueRemoteFollowupCount(2L)
                        .setOldestDueRemoteFollowupTickId(13L)
                        .setRemoteFollowupDrainLagMs(2000L)
                        .build())
                .build());
    TickRemediationServiceImpl service = new TickRemediationServiceImpl(gameSessionClient);

    RuntimeOwnershipStatusDto result = service.getRuntimeOwnershipStatus(1L, "7", null);

    assertThat(result.gameInstanceId()).isEqualTo(7L);
    assertThat(result.regionId()).isEqualTo("region-7");
    assertThat(result.pendingGameplayCommandCount()).isEqualTo(3L);
    assertThat(result.remoteFollowupDrainLagMs()).isEqualTo(2000L);
  }

  @Test
  void getRuntimeOwnershipStatusRejectsMismatchedRegion() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getRuntimeOwnershipStatus(1L, null, "region-7"))
        .thenReturn(
            GetRuntimeOwnershipStatusResponse.newBuilder()
                .setOwnership(
                    RuntimeOwnershipStatus.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("7")
                        .setRegionId("region-9")
                        .build())
                .build());
    TickRemediationServiceImpl service = new TickRemediationServiceImpl(gameSessionClient);

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, null, "region-7"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("500 INTERNAL_SERVER_ERROR");
  }

  @Test
  void getRuntimeOwnershipStatusRejectsMalformedGameInstanceIdBeforeDispatch() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    TickRemediationServiceImpl service = new TickRemediationServiceImpl(gameSessionClient);

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, "not-a-number", null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400 BAD_REQUEST")
        .hasMessageContaining("gameInstanceId must be numeric");
    Mockito.verifyNoInteractions(gameSessionClient);
  }

  @Test
  void getRuntimeOwnershipStatusRejectsZeroGameInstanceIdForRegionScope() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    when(gameSessionClient.getRuntimeOwnershipStatus(1L, null, "region-7"))
        .thenReturn(
            GetRuntimeOwnershipStatusResponse.newBuilder()
                .setOwnership(
                    RuntimeOwnershipStatus.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("0")
                        .setRegionId("region-7")
                        .build())
                .build());
    TickRemediationServiceImpl service = new TickRemediationServiceImpl(gameSessionClient);

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, null, "region-7"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("500 INTERNAL_SERVER_ERROR");
  }

  @Test
  void getRuntimeOwnershipStatusRejectsMissingOrAmbiguousScope() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    TickRemediationServiceImpl service = new TickRemediationServiceImpl(gameSessionClient);

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Exactly one of gameInstanceId or regionId is required");

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, "7", "r1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Exactly one of gameInstanceId or regionId is required");
    Mockito.verifyNoInteractions(gameSessionClient);
  }
}
