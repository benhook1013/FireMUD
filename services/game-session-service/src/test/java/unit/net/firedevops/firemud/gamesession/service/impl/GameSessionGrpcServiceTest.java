package net.firedevops.firemud.gamesession.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
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
import net.firedevops.firemud.gamesession.service.AccountPresenceQueryService;
import net.firedevops.firemud.gamesession.service.AccountPresenceSnapshot;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
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
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceRequest;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.gamesession.v1.QueryStateRequest;
import net.firedevops.firemud.gamesession.v1.QueryStateResponse;
import net.firedevops.firemud.gamesession.v1.RestartSessionRequest;
import net.firedevops.firemud.gamesession.v1.RestartSessionResponse;
import net.firedevops.firemud.gamesession.v1.ResumeTicksRequest;
import net.firedevops.firemud.gamesession.v1.ResumeTicksResponse;
import net.firedevops.firemud.gamesession.v1.StartSessionRequest;
import net.firedevops.firemud.gamesession.v1.StartSessionResponse;
import net.firedevops.firemud.gamesession.v1.StopSessionRequest;
import net.firedevops.firemud.gamesession.v1.StopSessionResponse;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagResponse;
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
  void queryAccountPresenceReturnsMappedSnapshots() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    AccountPresenceQueryService accountPresenceQueryService =
        Mockito.mock(AccountPresenceQueryService.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of());
    Mockito.when(accountPresenceQueryService.queryAccountPresence(1L, 42L, List.of(7L)))
        .thenReturn(
            List.of(
                new AccountPresenceSnapshot(
                    7L,
                    true,
                    9L,
                    99L,
                    "Ben",
                    net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState
                        .EXPLICIT_AFK,
                    Instant.parse("2026-04-11T06:15:30Z"),
                    AccountPresenceVisibilityPolicy.FRIENDS_ONLY)));
    GameSessionGrpcService service =
        new GameSessionGrpcService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            accountPresenceQueryService,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<QueryAccountPresenceResponse> ref = new AtomicReference<>();
    service.queryAccountPresence(
        QueryAccountPresenceRequest.newBuilder()
            .setTenantId("1")
            .setViewerAccountId("42")
            .addAccountIds("7")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryAccountPresenceResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals(1, ref.get().getPresencesCount());
    assertEquals("7", ref.get().getPresences(0).getAccountId());
    assertEquals(true, ref.get().getPresences(0).getOnline());
    assertEquals("Ben", ref.get().getPresences(0).getCharacterName());
    assertEquals(
        Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
        ref.get().getPresences(0).getLastSeenAtMs());
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
    Mockito.when(ipLimiter.canAccept("127.0.0.1", null)).thenReturn(true);
    Mockito.when(ipLimiter.tryRegister(Mockito.anyString(), Mockito.anyLong())).thenReturn(true);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
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
                        && request.ownerAccountId().equals(42L)),
            Mockito.eq(false));
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
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
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
    Mockito.when(ipLimiter.canAccept("1.2.3.4", null)).thenReturn(true);
    Mockito.when(ipLimiter.tryRegister("1.2.3.4", 1L)).thenReturn(false);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
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
  void startSessionRejectsAtIpPreflightWithoutStoppingExistingSession() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstance existing = new GameInstance();
    existing.setId(77L);
    existing.setTenantId(1L);
    existing.setOwnerAccountId(42L);
    existing.setStatus("RUNNING");
    Mockito.when(
            gameInstanceRepository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
                1L, 42L, "RUNNING"))
        .thenReturn(java.util.Optional.of(existing));
    Mockito.when(ipLimiter.canAccept("1.2.3.4", 77L)).thenReturn(false);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
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
    Mockito.verify(gameInstanceService, Mockito.never())
        .startSession(
            Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
            Mockito.anyBoolean());
    Mockito.verify(gameInstanceService, Mockito.never()).stopSession(Mockito.anyLong());
    Mockito.verify(ipLimiter, Mockito.never()).tryRegister(Mockito.anyString(), Mockito.anyLong());
  }

  @Test
  void startSessionReplacementTransfersIpAndStopsOldSessionAfterSuccess() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstance existing = new GameInstance();
    existing.setId(77L);
    existing.setTenantId(1L);
    existing.setOwnerAccountId(42L);
    existing.setStatus("RUNNING");
    Mockito.when(
            gameInstanceRepository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
                1L, 42L, "RUNNING"))
        .thenReturn(java.util.Optional.of(existing));
    Mockito.when(ipLimiter.canAccept("1.2.3.4", 77L)).thenReturn(true);
    Mockito.when(ipLimiter.transferRegistration("1.2.3.4", 77L, 88L)).thenReturn(true);
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenReturn(new GameInstanceDto(88L, 1L, "v1", null, 42L, "RUNNING"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
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

    assertEquals("88", ref.get().getSessionId());
    Mockito.verify(ipLimiter).transferRegistration("1.2.3.4", 77L, 88L);
    Mockito.verify(ipLimiter, Mockito.never()).tryRegister(Mockito.anyString(), Mockito.anyLong());
    Mockito.verify(ipLimiter, Mockito.never()).release(77L);
    Mockito.verify(gameInstanceService).stopSession(77L);
  }

  @Test
  void startSessionKeepsNewSessionWhenOldReplacementTeardownFails() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstance existing = new GameInstance();
    existing.setId(77L);
    existing.setTenantId(1L);
    existing.setOwnerAccountId(42L);
    existing.setStatus("RUNNING");
    Mockito.when(
            gameInstanceRepository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
                1L, 42L, "RUNNING"))
        .thenReturn(java.util.Optional.of(existing));
    Mockito.when(ipLimiter.canAccept("1.2.3.4", 77L)).thenReturn(true);
    Mockito.when(ipLimiter.transferRegistration("1.2.3.4", 77L, 88L)).thenReturn(true);
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenReturn(new GameInstanceDto(88L, 1L, "v1", null, 42L, "RUNNING"));
    Mockito.doThrow(new IllegalStateException("Failed to stop old session"))
        .when(gameInstanceService)
        .stopSession(77L);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
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

    assertEquals("88", ref.get().getSessionId());
    assertFalse(ref.get().hasError());
    Mockito.verify(gameInstanceService).stopSession(77L);
  }

  @Test
  void startSessionFailedReplacementDoesNotStopExistingSession() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstance existing = new GameInstance();
    existing.setId(77L);
    existing.setTenantId(1L);
    existing.setOwnerAccountId(42L);
    existing.setStatus("RUNNING");
    Mockito.when(
            gameInstanceRepository.findFirstByTenantIdAndOwnerAccountIdAndStatus(
                1L, 42L, "RUNNING"))
        .thenReturn(java.util.Optional.of(existing));
    Mockito.when(ipLimiter.canAccept("1.2.3.4", 77L)).thenReturn(true);
    Mockito.when(ipLimiter.transferRegistration("1.2.3.4", 77L, 88L)).thenReturn(false);
    Mockito.when(ipLimiter.tryRegister("1.2.3.4", 88L)).thenReturn(false);
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenReturn(new GameInstanceDto(88L, 1L, "v1", null, 42L, "RUNNING"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
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
    Mockito.verify(gameInstanceService).stopSession(88L);
    Mockito.verify(gameInstanceService, Mockito.never()).stopSession(77L);
    Mockito.verify(ipLimiter, Mockito.never()).release(77L);
  }

  @Test
  void startSessionRuntimeFailureReturnsInternalErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("admin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenThrow(new IllegalStateException("Failed to start session"));
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

    assertEquals("INTERNAL", ref.get().getError().getCode());
    assertEquals("Failed to start session", ref.get().getError().getMessage());
  }

  @Test
  void stopSessionRuntimeFailureReturnsInternalErrorDetail() {
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
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("admin")));
    Mockito.doThrow(new IllegalStateException("Failed to stop session"))
        .when(gameInstanceService)
        .stopSession(7L);
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

    AtomicReference<StopSessionResponse> ref = new AtomicReference<>();
    service.stopSession(
        StopSessionRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<StopSessionResponse>() {
          @Override
          public void onNext(StopSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(ref.get().getSuccess());
    assertEquals("INTERNAL", ref.get().getError().getCode());
    assertEquals("Failed to stop session", ref.get().getError().getMessage());
  }

  @Test
  void restartSessionRuntimeFailureReturnsInternalErrorDetail() {
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
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("admin")));
    Mockito.when(gameInstanceService.restartSession(7L))
        .thenThrow(new IllegalStateException("Failed to restart session"));
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

    AtomicReference<RestartSessionResponse> ref = new AtomicReference<>();
    service.restartSession(
        RestartSessionRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<RestartSessionResponse>() {
          @Override
          public void onNext(RestartSessionResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(ref.get().getSuccess());
    assertEquals("INTERNAL", ref.get().getError().getCode());
    assertEquals("Failed to restart session", ref.get().getError().getMessage());
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
  void enqueueCommandRuntimeFailureReturnsInternalErrorDetail() {
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
        .thenThrow(new IllegalStateException("boom"));
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
        new StreamObserver<>() {
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

    assertFalse(ref.get().getAccepted());
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void queryStateRuntimeFailureReturnsInternalErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameInstance instance = new GameInstance();
    instance.setId(7L);
    instance.setTenantId(9L);
    instance.setOwnerAccountId(42L);
    instance.setRuntimeVersion("v1");
    instance.setStatus("RUNNING");
    Mockito.when(gameInstanceRepository.findById(7L)).thenReturn(java.util.Optional.of(instance));
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("admin")));
    Mockito.when(tickService.queryState(7L)).thenThrow(new IllegalStateException("boom"));
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

    AtomicReference<QueryStateResponse> ref = new AtomicReference<>();
    service.queryState(
        QueryStateRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(QueryStateResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void toggleFeatureFlagRuntimeFailureReturnsInternalErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("admin")));
    Mockito.doThrow(new IllegalStateException("boom"))
        .when(featureFlagService)
        .toggleFlag(
            Mockito.any(net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest.class));
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

    AtomicReference<ToggleFeatureFlagResponse> ref = new AtomicReference<>();
    service.toggleFeatureFlag(
        ToggleFeatureFlagRequest.newBuilder()
            .setTenantId("9")
            .setName("x")
            .setEnabled(true)
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ToggleFeatureFlagResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(ref.get().getSuccess());
    assertEquals("INTERNAL", ref.get().getError().getCode());
  }

  @Test
  void pauseTicksRuntimeFailureReturnsInternalErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    Mockito.doThrow(new IllegalStateException("boom")).when(tickService).pauseTicks("backup");
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
        PauseTicksRequest.newBuilder().setReason("backup").build(),
        new StreamObserver<>() {
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

    assertFalse(ref.get().getSuccess());
    assertEquals("INTERNAL", ref.get().getError().getCode());
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
            Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
            Mockito.anyBoolean());
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
            Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
            Mockito.anyBoolean());
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
