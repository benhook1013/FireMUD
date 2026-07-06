package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.DisconnectDeduplicationService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectRequest;
import net.firedevops.firemud.tcpproxy.v1.NotifyDisconnectResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TcpProxyServiceImplTest {
  @Test
  void notifyDisconnectSavesSuspendedState() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("12")
            .setGameInstanceId("12")
            .setTenantId("7")
            .build(),
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
  void notifyDisconnectAddsGameplayLoggingContext() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));
    Mockito.doAnswer(
            ignored -> {
              assertEquals("7", MDC.get("tenantId"));
              assertEquals("12", MDC.get("gameInstanceId"));
              assertEquals(null, MDC.get("characterId"));
              return null;
            })
        .when(sessionStateService)
        .saveState(Mockito.any());

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("12")
            .setGameInstanceId("12")
            .setTenantId("7")
            .build(),
        observerFor(ref));

    assertEquals("OK", ref.get().getError().getCode());
    assertEquals(null, MDC.get("tenantId"));
    assertEquals(null, MDC.get("gameInstanceId"));
    assertEquals(null, MDC.get("characterId"));
  }

  @Test
  void notifyDisconnectRejectsInvalidTenant(CapturedOutput output) {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);
    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("12")
            .setGameInstanceId("12")
            .setTenantId("bad")
            .build(),
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
    assertEquals("tenantId must be a number", ref.get().getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    org.assertj.core.api.Assertions.assertThat(output)
        .contains(
            "NotifyDisconnect returned app error INVALID_ARGUMENT: tenantId must be a number");
    Mockito.verifyNoInteractions(sessionStateService);
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void notifyDisconnectRejectsZeroGameInstanceId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);
    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("12")
            .setGameInstanceId("0")
            .setTenantId("7")
            .build(),
        observerFor(ref));

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("gameInstanceId must be positive", ref.get().getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    Mockito.verifyNoInteractions(sessionStateService);
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void notifyDisconnectRejectsZeroTenantId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);
    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("12")
            .setGameInstanceId("12")
            .setTenantId("0")
            .build(),
        observerFor(ref));

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    assertEquals(
        1.0, meterRegistry.get("grpc.app_error").tag("code", "INVALID_ARGUMENT").counter().count());
    Mockito.verifyNoInteractions(sessionStateService);
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void duplicateDisconnectHintsAreIgnoredAndMetered() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true, false);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    NotifyDisconnectRequest request =
        NotifyDisconnectRequest.newBuilder()
            .setGameInstanceId("12")
            .setTenantId("7")
            .setProxyConnectionId("proxy-1")
            .setDisconnectSequence(1L)
            .build();

    AtomicReference<NotifyDisconnectResponse> first = new AtomicReference<>();
    AtomicReference<NotifyDisconnectResponse> second = new AtomicReference<>();

    service.notifyDisconnect(request, observerFor(first));
    service.notifyDisconnect(request, observerFor(second));

    assertEquals("OK", first.get().getError().getCode());
    assertEquals("OK", second.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.duplicate").counter().count());
    Mockito.verify(sessionStateService, Mockito.times(1)).saveState(Mockito.any());
  }

  @Test
  void lateDisconnectHintsAreIgnoredAndMetered() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true, false);
    GameInstance entity = buildEntity(12L, 7L);
    Mockito.when(repository.findById(12L)).thenReturn(Optional.of(entity));

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    NotifyDisconnectRequest firstRequest =
        NotifyDisconnectRequest.newBuilder()
            .setGameInstanceId("12")
            .setTenantId("7")
            .setProxyConnectionId("proxy-1")
            .setDisconnectSequence(2L)
            .build();
    NotifyDisconnectRequest lateRequest =
        NotifyDisconnectRequest.newBuilder()
            .setGameInstanceId("12")
            .setTenantId("7")
            .setProxyConnectionId("proxy-1")
            .setDisconnectSequence(1L)
            .build();

    AtomicReference<NotifyDisconnectResponse> first = new AtomicReference<>();
    AtomicReference<NotifyDisconnectResponse> second = new AtomicReference<>();

    service.notifyDisconnect(firstRequest, observerFor(first));
    service.notifyDisconnect(lateRequest, observerFor(second));

    assertEquals("OK", first.get().getError().getCode());
    assertEquals("OK", second.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.duplicate").counter().count());
    Mockito.verify(sessionStateService, Mockito.times(1)).saveState(Mockito.any());
  }

  @Test
  void disconnectWithoutBootstrapMetadataIsMetered() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(NotifyDisconnectRequest.newBuilder().build(), observerFor(ref));

    assertEquals("OK", ref.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verifyNoInteractions(sessionStateService);
  }

  @Test
  void disconnectWithoutGameInstanceMetadataDoesNotFallBackToSessionId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("12")
            .setTenantId("7")
            .setProxyConnectionId("proxy-1")
            .setDisconnectSequence(1L)
            .build(),
        observerFor(ref));

    assertEquals("OK", ref.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verify(gameplayPresenceLifecycleService)
        .recordDisconnected(
            12L,
            net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition
                .TRANSPORT_LOSS);
    Mockito.verifyNoInteractions(sessionStateService);
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void disconnectWithoutGameInstanceMetadataIgnoresZeroSessionId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("0")
            .setTenantId("7")
            .setProxyConnectionId("proxy-1")
            .setDisconnectSequence(1L)
            .build(),
        observerFor(ref));

    assertEquals("OK", ref.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verifyNoInteractions(gameplayPresenceLifecycleService);
    Mockito.verifyNoInteractions(sessionStateService);
    Mockito.verifyNoInteractions(repository);
  }

  @Test
  void disconnectWithoutGameInstanceMetadataIgnoresNegativeSessionId() {
    GameInstanceRepository repository = Mockito.mock(GameInstanceRepository.class);
    SessionStateService sessionStateService = Mockito.mock(SessionStateService.class);
    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    PingService pingService = Mockito.mock(PingService.class);
    GameplayPresenceLifecycleService gameplayPresenceLifecycleService =
        Mockito.mock(GameplayPresenceLifecycleService.class);
    DisconnectDeduplicationService disconnectDeduplicationService =
        Mockito.mock(DisconnectDeduplicationService.class);
    Mockito.when(
            disconnectDeduplicationService.shouldProcess(Mockito.anyString(), Mockito.anyLong()))
        .thenReturn(true);

    TcpProxyServiceImpl service =
        new TcpProxyServiceImpl(
            repository,
            sessionStateService,
            meterRegistry,
            pingService,
            disconnectDeduplicationService,
            gameplayPresenceLifecycleService);

    AtomicReference<NotifyDisconnectResponse> ref = new AtomicReference<>();
    service.notifyDisconnect(
        NotifyDisconnectRequest.newBuilder()
            .setSessionId("-1")
            .setTenantId("7")
            .setProxyConnectionId("proxy-1")
            .setDisconnectSequence(1L)
            .build(),
        observerFor(ref));

    assertEquals("OK", ref.get().getError().getCode());
    assertEquals(
        1.0, meterRegistry.get("gamesession.notifydisconnect.missing_context").counter().count());
    Mockito.verifyNoInteractions(gameplayPresenceLifecycleService);
    Mockito.verifyNoInteractions(sessionStateService);
    Mockito.verifyNoInteractions(repository);
  }

  private static StreamObserver<NotifyDisconnectResponse> observerFor(
      AtomicReference<NotifyDisconnectResponse> ref) {
    return new StreamObserver<>() {
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
    };
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
