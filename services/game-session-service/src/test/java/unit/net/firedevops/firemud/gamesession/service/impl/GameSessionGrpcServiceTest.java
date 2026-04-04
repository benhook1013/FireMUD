package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.GetTickStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetTickStatusResponse;
import net.firedevops.firemud.gamesession.v1.PauseTicksRequest;
import net.firedevops.firemud.gamesession.v1.PauseTicksResponse;
import net.firedevops.firemud.gamesession.v1.PingRequest;
import net.firedevops.firemud.gamesession.v1.PingResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksResponse;
import net.firedevops.firemud.gamesession.v1.StartSessionRequest;
import net.firedevops.firemud.gamesession.v1.StartSessionResponse;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionGrpcServiceTest {
  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<PingResponse> ref = new AtomicReference<>();
    service.ping(
        PingRequest.getDefaultInstance(),
        new StreamObserver<PingResponse>() {
          @Override
          public void onNext(PingResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", ref.get().getMessage());
    assertEquals("OK", ref.get().getError().getCode());
    assertEquals("pong", ref.get().getError().getMessage());
  }

  @Test
  void startSessionReturnsId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.tryRegister(Mockito.anyString(), Mockito.anyLong())).thenReturn(true);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class)))
        .thenReturn(new GameInstanceDto(1L, 1L, "v1", null, 42L, "RUNNING"));
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setRuntimeVersion("v1")
            .setScriptPatchVersion("")
            .setClientIp("127.0.0.1")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("1", ref.get().getSessionId());
    Mockito.verify(ipLimiter).tryRegister("127.0.0.1", 1L);
    Mockito.verify(gameInstanceService)
        .startSession(
            Mockito.argThat(
                request ->
                    request.tenantId().equals(1L)
                        && request.runtimeVersion().equals("v1")
                        && request.ownerAccountId().equals(42L)));
  }

  @Test
  void pauseAndResumeTicksDelegateToService() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    service.pauseTicks(
        PauseTicksRequest.newBuilder().setReason("backup").build(),
        new StreamObserver<PauseTicksResponse>() {
          @Override
          public void onNext(PauseTicksResponse value) {}

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    Mockito.verify(tickService).pauseTicks("backup");

    Mockito.when(tickService.getTickStatus()).thenReturn(TickStatus.TICK_STATUS_PAUSED);

    AtomicReference<GetTickStatusResponse> statusRef = new AtomicReference<>();
    service.getTickStatus(
        GetTickStatusRequest.getDefaultInstance(),
        new StreamObserver<GetTickStatusResponse>() {
          @Override
          public void onNext(GetTickStatusResponse value) {
            statusRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals(TickStatus.TICK_STATUS_PAUSED, statusRef.get().getStatus());

    service.resumeTicks(
        ResumeTicksRequest.newBuilder().setReason("done").build(),
        new StreamObserver<ResumeTicksResponse>() {
          @Override
          public void onNext(ResumeTicksResponse value) {}

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    Mockito.verify(tickService).resumeTicks("done");
  }

  @Test
  void startSessionWithoutClientIpSkipsRegistration() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class)))
        .thenReturn(new GameInstanceDto(1L, 1L, "v1", null, 42L, "RUNNING"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setRuntimeVersion("v1")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("1", ref.get().getSessionId());
    Mockito.verify(ipLimiter, Mockito.never()).tryRegister(Mockito.anyString(), Mockito.anyLong());
  }

  @Test
  void startSessionRejectedWhenIpReservationFailsStopsInstance() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.tryRegister("1.2.3.4", 1L)).thenReturn(false);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class)))
        .thenReturn(new GameInstanceDto(1L, 1L, "v1", null, 42L, "RUNNING"));
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setRuntimeVersion("v1")
            .setScriptPatchVersion("")
            .setClientIp("1.2.3.4")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("CONNECTION_LIMIT", ref.get().getError().getCode());
    Mockito.verify(gameInstanceService).stopSession(1L);
  }

  @Test
  void enqueueCommandRespectsRateLimit() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstance instance = new GameInstance();
    instance.setId(99L);
    instance.setTenantId(9L);
    instance.setOwnerAccountId(42L);
    instance.setRuntimeVersion("v1");
    instance.setStatus("RUNNING");
    Mockito.when(gameInstanceRepository.findById(99L)).thenReturn(java.util.Optional.of(instance));
    SessionContext.setContext("42", List.of(), Map.of());
    Mockito.when(
            textCommandInterpreter.interpret(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.failure("RATE_LIMIT", "Command rate limit exceeded")));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse> ref =
        new AtomicReference<>();
    service.enqueueCommand(
        net.firedevops.firemud.gamesession.v1.EnqueueCommandRequest.newBuilder()
            .setSessionId("99")
            .setCommand("look")
            .build(),
        new StreamObserver<net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse>() {
          @Override
          public void onNext(net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse value) {
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
    assertFalse(ref.get().getAccepted());
  }

  @Test
  void startSessionRejectsUnscopedCaller() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("99", List.of(), Map.of());
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setRuntimeVersion("v1")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    Mockito.verify(gameInstanceService, Mockito.never())
        .startSession(
            Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class));
    Mockito.verify(ipLimiter, Mockito.never()).tryRegister(Mockito.anyString(), Mockito.anyLong());
  }

  @Test
  void startSessionRejectsOwnerMismatchWithinTenant() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("99", List.of(), Map.of("1", List.of("admin")));
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setRuntimeVersion("v1")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    Mockito.verify(gameInstanceService, Mockito.never())
        .startSession(
            Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class));
  }

  @Test
  void pauseTicksRejectsNonAdminCaller() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("99", List.of(), Map.of());
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<PauseTicksResponse> ref = new AtomicReference<>();
    service.pauseTicks(
        PauseTicksRequest.newBuilder().setReason("maintenance").build(),
        new StreamObserver<PauseTicksResponse>() {
          @Override
          public void onNext(PauseTicksResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    Mockito.verify(tickService, Mockito.never()).pauseTicks(Mockito.anyString());
  }

  @Test
  void stopSessionRejectsWrongOwner() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(9L);
    instance.setOwnerAccountId(42L);
    instance.setRuntimeVersion("v1");
    instance.setStatus("RUNNING");
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(java.util.Optional.of(instance));
    SessionContext.setContext("99", List.of(), Map.of());
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    service.stopSession(
        net.firedevops.firemud.gamesession.v1.StopSessionRequest.newBuilder()
            .setSessionId("7")
            .build(),
        new StreamObserver<net.firedevops.firemud.gamesession.v1.StopSessionResponse>() {
          @Override
          public void onNext(net.firedevops.firemud.gamesession.v1.StopSessionResponse value) {
            assertEquals("PERMISSION_DENIED", value.getError().getCode());
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });
    Mockito.verify(gameInstanceService, Mockito.never()).stopSession(Mockito.anyLong());
  }
}
