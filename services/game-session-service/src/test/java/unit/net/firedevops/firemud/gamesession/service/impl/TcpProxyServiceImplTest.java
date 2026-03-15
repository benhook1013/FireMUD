package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TcpProxyServiceImplTest {
  @Test
  void notifyDisconnectSavesSuspendedState() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            new DevIsolatedProperties(false));

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder().setSessionId("12").setTenantId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("OK", ref.get().getError().getCode());
    ArgumentCaptor<GameInstanceDto> stateCaptor = ArgumentCaptor.forClass(GameInstanceDto.class);
    Mockito.verify(sessionStateService).saveState(stateCaptor.capture());
    assertEquals("SUSPENDED", stateCaptor.getValue().status());
  }

  @Test
  void notifyDisconnectRejectsInvalidTenant() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            new DevIsolatedProperties(false));

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder().setSessionId("12").setTenantId("bad").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(NotifyDisconnectResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    Mockito.verifyNoInteractions(sessionStateService);
  }

  private static GameInstance buildEntity(long sessionId, long tenantId) {
    GameInstance entity = new GameInstance();
    entity.setId(sessionId);
    entity.setTenantId(tenantId);
    entity.setRuntimeVersion("rt");
    entity.setScriptPatchVersion("patch");
    entity.setOwnerAccountId(99L);
    entity.setStatus("RUNNING");
    return entity;
  }
}
