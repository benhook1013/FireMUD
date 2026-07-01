package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.GetRuntimeOwnershipStatusResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.RuntimeOwnershipStatus;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.dto.RuntimeOwnershipStatusDto;
import net.firedevops.firemud.loggingadmin.dto.TickRemediationRequest;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    LogEventService logEventService = Mockito.mock(LogEventService.class);
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
    TickRemediationServiceImpl service =
        new TickRemediationServiceImpl(gameSessionClient, logEventService);

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
    LogEventService logEventService = Mockito.mock(LogEventService.class);
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
    TickRemediationServiceImpl service =
        new TickRemediationServiceImpl(gameSessionClient, logEventService);

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, null, "region-7"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("500 INTERNAL_SERVER_ERROR");
  }

  @Test
  void pauseForGameInstanceForwardsAndAudits() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    when(gameSessionClient.pauseTicksForScope(any()))
        .thenReturn(PauseTicksForScopeResponse.newBuilder().setSuccess(true).build());
    SessionContext.setContext("42", java.util.List.of("platformAdmin"), java.util.Map.of());
    TickRemediationServiceImpl service =
        new TickRemediationServiceImpl(gameSessionClient, logEventService);

    var result = service.pauseTicksForScope(new TickRemediationRequest(1L, "7", null, "maint"));

    assertThat(result.action()).isEqualTo("pause");
    assertThat(result.scopeType()).isEqualTo("game_instance");
    assertThat(result.scopeId()).isEqualTo("7");
    ArgumentCaptor<CreateLogEventRequest> auditCaptor =
        ArgumentCaptor.forClass(CreateLogEventRequest.class);
    verify(logEventService).createLogEvent(auditCaptor.capture());
    assertThat(auditCaptor.getValue().type()).isEqualTo("tick_remediation_pause");
    assertThat(auditCaptor.getValue().tenantId()).isEqualTo(1L);
    assertThat(auditCaptor.getValue().accountId()).isEqualTo(42L);
    assertThat(auditCaptor.getValue().message()).contains("game_instance=7");
  }

  @Test
  void resumeForRegionPropagatesGrpcValidationError() {
    GameSessionControlPlaneClient gameSessionClient =
        Mockito.mock(GameSessionControlPlaneClient.class);
    LogEventService logEventService = Mockito.mock(LogEventService.class);
    when(gameSessionClient.resumeTicksForScope(any()))
        .thenReturn(
            ResumeTicksForScopeResponse.newBuilder()
                .setSuccess(false)
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("region_id is not supported")
                        .build())
                .build());
    SessionContext.setContext("42", java.util.List.of("platformAdmin"), java.util.Map.of());
    TickRemediationServiceImpl service =
        new TickRemediationServiceImpl(gameSessionClient, logEventService);

    assertThatThrownBy(
            () -> service.resumeTicksForScope(new TickRemediationRequest(1L, null, "region-1", "")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400 BAD_REQUEST");
  }

  @Test
  void pauseRejectsMissingOrAmbiguousScope() {
    TickRemediationServiceImpl service =
        new TickRemediationServiceImpl(
            Mockito.mock(GameSessionControlPlaneClient.class), Mockito.mock(LogEventService.class));

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Exactly one of gameInstanceId or regionId is required");

    assertThatThrownBy(() -> service.getRuntimeOwnershipStatus(1L, "7", "r1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Exactly one of gameInstanceId or regionId is required");

    assertThatThrownBy(
            () -> service.pauseTicksForScope(new TickRemediationRequest(1L, null, null, "")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Exactly one of gameInstanceId or regionId is required");

    assertThatThrownBy(
            () -> service.pauseTicksForScope(new TickRemediationRequest(1L, "7", "r1", "")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Exactly one of gameInstanceId or regionId is required");
  }
}
