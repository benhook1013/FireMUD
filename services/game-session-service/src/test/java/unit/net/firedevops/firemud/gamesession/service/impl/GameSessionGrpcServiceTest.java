package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameSessionGrpcServiceTest {
  @Test
  void pingReturnsPong() {
    PingService pingService = Mockito.mock(PingService.class);
    Mockito.when(pingService.ping()).thenReturn("pong");
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.canAccept(Mockito.anyString())).thenReturn(true);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
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
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.canAccept(Mockito.anyString())).thenReturn(true);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class)))
        .thenReturn(new GameInstanceDto(1L, 1L, "v1", null, 0L, "RUNNING"));
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
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
  }

  @Test
  void pauseAndResumeTicksDelegateToService() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.canAccept(Mockito.anyString())).thenReturn(true);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
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
  void startSessionRejectedWhenIpLimitExceeded() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.canAccept("1.2.3.4")).thenReturn(false);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> ref = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setRuntimeVersion("v1")
            .setClientIp("1.2.3.4")
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
  }

  @Test
  void enqueueCommandRespectsRateLimit() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    Mockito.when(ipLimiter.canAccept(Mockito.anyString())).thenReturn(true);
    Mockito.when(
            textCommandInterpreter.interpret(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyBoolean()))
        .thenReturn(
            new TextCommandInterpretationResult(
                CommandEnqueueResult.failure("RATE_LIMIT", "Command rate limit exceeded"), null));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse> ref =
        new AtomicReference<>();
    service.enqueueCommand(
        net.firedevops.firemud.gamesession.v1.EnqueueCommandRequest.newBuilder()
            .setSessionId("1")
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
  }
}
