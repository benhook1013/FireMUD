package net.firedevops.firemud.loggingadmin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.PauseTicksForScopeResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksForScopeResponse;
import net.firedevops.firemud.loggingadmin.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
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
