package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.config.DevIsolatedProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.PingService;
import net.firedevops.firemud.service.SessionStateService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputRequest;
import net.firedevops.firemud.tcpproxy.v1.PushBufferedInputResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TcpProxyServiceImplTest {
  @Test
  void notifyDisconnectSavesSuspendedState() {
    CommandService commandService = Mockito.mock(CommandService.class);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            commandService,
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
    CommandService commandService = Mockito.mock(CommandService.class);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            commandService,
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

  @Test
  void pushBufferedInputEnqueuesCommands() {
    CommandService commandService = Mockito.mock(CommandService.class);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameInstance entity = buildEntity(55L, 3L);
    Mockito.when(repository.findById(55L)).thenReturn(Optional.of(entity));
    Mockito.when(commandService.enqueue("55", "north", false))
        .thenReturn(CommandEnqueueResult.success());
    Mockito.when(commandService.enqueue("55", "look", false))
        .thenReturn(CommandEnqueueResult.success());

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            commandService,
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            new DevIsolatedProperties(false));

    AtomicReference<PushBufferedInputResponse> ref = new AtomicReference<>();
    service.pushBufferedInput(
        PushBufferedInputRequest.newBuilder()
            .setSessionId("55")
            .setTenantId("3")
            .addCommands("north")
            .addCommands("look")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PushBufferedInputResponse value) {
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
    Mockito.verify(commandService).enqueue("55", "north", false);
    Mockito.verify(commandService).enqueue("55", "look", false);
  }

  @Test
  void pushBufferedInputStopsOnFailure() {
    CommandService commandService = Mockito.mock(CommandService.class);
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameInstance entity = buildEntity(22L, 8L);
    Mockito.when(repository.findById(22L)).thenReturn(Optional.of(entity));
    Mockito.when(commandService.enqueue("22", "first", false))
        .thenReturn(CommandEnqueueResult.success());
    Mockito.when(commandService.enqueue("22", "second", false))
        .thenReturn(CommandEnqueueResult.failure("RATE_LIMIT", "Too fast"));

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            commandService,
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            new DevIsolatedProperties(false));

    AtomicReference<PushBufferedInputResponse> ref = new AtomicReference<>();
    service.pushBufferedInput(
        PushBufferedInputRequest.newBuilder()
            .setSessionId("22")
            .setTenantId("8")
            .addCommands("first")
            .addCommands("second")
            .addCommands("third")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PushBufferedInputResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("RATE_LIMIT", ref.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "RATE_LIMIT").counter().count());
    Mockito.verify(commandService).enqueue("22", "first", false);
    Mockito.verify(commandService).enqueue("22", "second", false);
    Mockito.verify(commandService, Mockito.never()).enqueue("22", "third", false);
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
