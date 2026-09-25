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
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.AccountPresenceQueryService;
import net.firedevops.firemud.gamesession.service.AccountPresenceSnapshot;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.support.TestGameplayWorldCatalogs;
import net.firedevops.firemud.gamesession.v1.GetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.GetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.GetTickStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetTickStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListGameplayRealmsRequest;
import net.firedevops.firemud.gamesession.v1.ListGameplayRealmsResponse;
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

  private static GameSessionGrpcService newService(
      PingService pingService,
      GameInstanceService gameInstanceService,
      FeatureFlagService featureFlagService,
      TextCommandInterpreter textCommandInterpreter,
      GameInstanceRepository gameInstanceRepository,
      TickService tickService,
      SimpleMeterRegistry meterRegistry,
      IpConnectionLimiter ipConnectionLimiter) {
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    return new GameSessionGrpcService(
        pingService,
        gameInstanceService,
        featureFlagService,
        textCommandInterpreter,
        gameInstanceRepository,
        (tenantId, viewerAccountId, accountIds) -> List.of(),
        pointerAuthorityService,
        TestGameplayWorldCatalogs.fromProperties(new GameplayCatalogProperties()),
        tickService,
        meterRegistry,
        ipConnectionLimiter);
  }

  private static GameSessionGrpcService newService(
      PingService pingService,
      GameInstanceService gameInstanceService,
      FeatureFlagService featureFlagService,
      TextCommandInterpreter textCommandInterpreter,
      GameInstanceRepository gameInstanceRepository,
      AccountPresenceQueryService accountPresenceQueryService,
      TickService tickService,
      SimpleMeterRegistry meterRegistry,
      IpConnectionLimiter ipConnectionLimiter) {
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    return new GameSessionGrpcService(
        pingService,
        gameInstanceService,
        featureFlagService,
        textCommandInterpreter,
        gameInstanceRepository,
        accountPresenceQueryService,
        pointerAuthorityService,
        TestGameplayWorldCatalogs.fromProperties(new GameplayCatalogProperties()),
        tickService,
        meterRegistry,
        ipConnectionLimiter);
  }

  private static GameSessionGrpcService newService(
      PingService pingService,
      GameInstanceService gameInstanceService,
      FeatureFlagService featureFlagService,
      TextCommandInterpreter textCommandInterpreter,
      GameInstanceRepository gameInstanceRepository,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameplayWorldCatalog gameplayWorldCatalog,
      TickService tickService,
      SimpleMeterRegistry meterRegistry,
      IpConnectionLimiter ipConnectionLimiter) {
    return new GameSessionGrpcService(
        pingService,
        gameInstanceService,
        featureFlagService,
        textCommandInterpreter,
        gameInstanceRepository,
        (tenantId, viewerAccountId, accountIds) -> List.of(),
        gameplayAdmissionPointerAuthorityService,
        gameplayWorldCatalog,
        tickService,
        meterRegistry,
        ipConnectionLimiter);
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
        newService(
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
                    "ISOLATED",
                    "demo",
                    "Demo World",
                    "production",
                    "Live Realm",
                    17L,
                    99L,
                    "Ben",
                    net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState
                        .EXPLICIT_AFK,
                    Instant.parse("2026-04-11T06:15:30Z"),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS)));
    GameSessionGrpcService service =
        newService(
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
    assertEquals("demo", ref.get().getPresences(0).getWorldSlug());
    assertEquals("Demo World", ref.get().getPresences(0).getWorldDisplayName());
    assertEquals(17L, ref.get().getPresences(0).getPointerVersion());
    assertEquals("production", ref.get().getPresences(0).getRealmSlug());
    assertEquals("Live Realm", ref.get().getPresences(0).getRealmDisplayName());
    assertEquals("Ben", ref.get().getPresences(0).getCharacterName());
    assertEquals(
        Instant.parse("2026-04-11T06:15:30Z").toEpochMilli(),
        ref.get().getPresences(0).getLastSeenAtMs());
    assertEquals(
        net.firedevops.firemud.gamesession.v1.AccountRecentPresenceDisposition
            .ACCOUNT_RECENT_PRESENCE_DISPOSITION_TRANSPORT_LOSS,
        ref.get().getPresences(0).getRecentDisposition());
  }

  @Test
  void queryAccountPresenceRejectsMalformedAccountId() {
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
    GameSessionGrpcService service =
        newService(
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
            .addAccountIds("abc")
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be a number", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountPresenceQueryService);
  }

  @Test
  void queryAccountPresenceRejectsZeroAccountId() {
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
    GameSessionGrpcService service =
        newService(
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
            .addAccountIds("0")
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountPresenceQueryService);
  }

  @Test
  void queryAccountPresenceRejectsTooManyAccountIdsBeforeParsing() {
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
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            accountPresenceQueryService,
            tickService,
            meterRegistry,
            ipLimiter);

    QueryAccountPresenceRequest.Builder request =
        QueryAccountPresenceRequest.newBuilder().setTenantId("1").setViewerAccountId("42");
    for (int index = 0; index < 101; index++) {
      request.addAccountIds(index == 100 ? "not-a-number" : Long.toString(index + 1L));
    }

    AtomicReference<QueryAccountPresenceResponse> ref = new AtomicReference<>();
    service.queryAccountPresence(
        request.build(),
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("accountIds must contain at most 100 entries", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(accountPresenceQueryService);
  }

  @Test
  void gameplayCatalogRpcReturnsCanonicalRealmData() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayAdmissionPointerSnapshot authoritativeSnapshot =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            7L,
            44L,
            17L,
            true,
            false,
            false,
            "ISOLATED",
            "COPIED_ONLY",
            29L,
            java.util.UUID.fromString("8a1df0f1-1b57-465e-9c4b-bb34f8153d31"),
            java.util.UUID.fromString("2ea958e0-13a2-41d0-9c39-59a96cf31412"));
    Mockito.when(pointerAuthorityService.listPointers()).thenReturn(List.of(authoritativeSnapshot));
    Mockito.when(pointerAuthorityService.findPointer(7L, "demo", "production"))
        .thenReturn(java.util.Optional.of(authoritativeSnapshot));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            pointerAuthorityService,
            new GameplayWorldCatalog(pointerAuthorityService),
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<ListGameplayRealmsResponse> realmsRef = new AtomicReference<>();
    service.listGameplayRealms(
        ListGameplayRealmsRequest.newBuilder().setWorldSlug("demo").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListGameplayRealmsResponse value) {
            realmsRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals(1, realmsRef.get().getRealmsCount());
    assertEquals("production", realmsRef.get().getRealms(0).getRealmSlug());
    assertEquals("ISOLATED", realmsRef.get().getRealms(0).getStateScope());
    assertEquals(17L, realmsRef.get().getRealms(0).getPointerVersion());
    assertEquals(29L, realmsRef.get().getRealms(0).getCatalogRevision());
    assertEquals("8a1df0f1-1b57-465e-9c4b-bb34f8153d31", realmsRef.get().getRealms(0).getRealmId());
    assertEquals(
        "2ea958e0-13a2-41d0-9c39-59a96cf31412",
        realmsRef.get().getRealms(0).getPlayableStateNamespaceId());

    AtomicReference<GetAdmissionPointerResponse> pointerRef = new AtomicReference<>();
    service.getAdmissionPointer(
        GetAdmissionPointerRequest.newBuilder()
            .setTenantId("7")
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetAdmissionPointerResponse value) {
            pointerRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("44", pointerRef.get().getAdmissionPointer().getGameInstanceId());
    assertEquals(17L, pointerRef.get().getAdmissionPointer().getPointerVersion());
    assertEquals(29L, pointerRef.get().getAdmissionPointer().getCatalogRevision());
    assertEquals(
        "8a1df0f1-1b57-465e-9c4b-bb34f8153d31",
        pointerRef.get().getAdmissionPointer().getRealmId());
    assertEquals(
        "2ea958e0-13a2-41d0-9c39-59a96cf31412",
        pointerRef.get().getAdmissionPointer().getPlayableStateNamespaceId());
    Mockito.verify(pointerAuthorityService).findPointer(7L, "demo", "production");
  }

  @Test
  void missingCatalogRevisionFailsClosedForCatalogAndAdmissionPointerReads() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameplayAdmissionPointerSnapshot missingRevision =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            7L,
            44L,
            17L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            0L);
    Mockito.when(pointerAuthorityService.listPointers()).thenReturn(List.of(missingRevision));
    Mockito.when(pointerAuthorityService.findPointer(7L, "demo", "production"))
        .thenReturn(java.util.Optional.of(missingRevision));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            pointerAuthorityService,
            new GameplayWorldCatalog(pointerAuthorityService),
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<ListGameplayRealmsResponse> realmsRef = new AtomicReference<>();
    service.listGameplayRealms(
        ListGameplayRealmsRequest.newBuilder().setWorldSlug("demo").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListGameplayRealmsResponse value) {
            realmsRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", realmsRef.get().getError().getCode());
    assertEquals(0, realmsRef.get().getRealmsCount());

    AtomicReference<GetAdmissionPointerResponse> pointerRef = new AtomicReference<>();
    service.getAdmissionPointer(
        GetAdmissionPointerRequest.newBuilder()
            .setTenantId("7")
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetAdmissionPointerResponse value) {
            pointerRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", pointerRef.get().getError().getCode());
    assertFalse(pointerRef.get().hasAdmissionPointer());
  }

  @Test
  void missingStableRealmIdentityFailsClosedForCatalogReads() {
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    GameplayAdmissionPointerSnapshot missingIdentity =
        new GameplayAdmissionPointerSnapshot(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            7L,
            44L,
            17L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            29L);
    Mockito.when(pointerAuthorityService.listPointers()).thenReturn(List.of(missingIdentity));
    Mockito.when(pointerAuthorityService.findPointer(7L, "demo", "production"))
        .thenReturn(java.util.Optional.of(missingIdentity));
    GameSessionGrpcService service =
        newService(
            Mockito.mock(PingService.class),
            Mockito.mock(GameInstanceService.class),
            Mockito.mock(FeatureFlagService.class),
            Mockito.mock(TextCommandInterpreter.class),
            Mockito.mock(GameInstanceRepository.class),
            pointerAuthorityService,
            new GameplayWorldCatalog(pointerAuthorityService),
            Mockito.mock(TickService.class),
            new SimpleMeterRegistry(),
            Mockito.mock(IpConnectionLimiter.class));

    AtomicReference<ListGameplayRealmsResponse> response = new AtomicReference<>();
    service.listGameplayRealms(
        ListGameplayRealmsRequest.newBuilder().setWorldSlug("demo").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ListGameplayRealmsResponse value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", response.get().getError().getCode());
    assertEquals(0, response.get().getRealmsCount());

    AtomicReference<GetAdmissionPointerResponse> pointerResponse = new AtomicReference<>();
    service.getAdmissionPointer(
        GetAdmissionPointerRequest.newBuilder()
            .setTenantId("7")
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetAdmissionPointerResponse value) {
            pointerResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("ADMISSION_POINTER_UNAVAILABLE", pointerResponse.get().getError().getCode());
    assertFalse(pointerResponse.get().hasAdmissionPointer());
  }

  @Test
  void catalogPolicyRevisionAdvancesIndependentlyFromPointerVersion() {
    GameplayAdmissionPointerRepository pointerRepository =
        Mockito.mock(GameplayAdmissionPointerRepository.class);
    GameplayAdmissionPointerEventRepository eventRepository =
        Mockito.mock(GameplayAdmissionPointerEventRepository.class);
    AtomicReference<GameplayAdmissionPointer> currentPointer = new AtomicReference<>();
    Mockito.when(pointerRepository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production"))
        .thenAnswer(invocation -> java.util.Optional.ofNullable(currentPointer.get()));
    Mockito.when(pointerRepository.save(Mockito.any(GameplayAdmissionPointer.class)))
        .thenAnswer(
            invocation -> {
              GameplayAdmissionPointer pointer = invocation.getArgument(0);
              if (pointer.getId() == null) {
                pointer.setId(11L);
              }
              currentPointer.set(pointer);
              return pointer;
            });
    Mockito.when(eventRepository.save(Mockito.any(GameplayAdmissionPointerEvent.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    DatabaseGameplayAdmissionPointerAuthorityService authorityService =
        new DatabaseGameplayAdmissionPointerAuthorityService(pointerRepository, eventRepository);

    GameplayAdmissionPointerSnapshot created =
        authorityService.upsertPointer(pointerMutation(44L, true, 0L));
    GameplayAdmissionPointerSnapshot policyChanged =
        authorityService.upsertPointer(pointerMutation(44L, false, 1L));
    GameplayAdmissionPointerSnapshot routeChanged =
        authorityService.upsertPointer(pointerMutation(45L, false, 2L));

    assertEquals(1L, created.catalogRevision());
    assertEquals(1L, created.pointerVersion());
    assertEquals(2L, policyChanged.catalogRevision());
    assertEquals(2L, policyChanged.pointerVersion());
    assertEquals(2L, routeChanged.catalogRevision());
    assertEquals(3L, routeChanged.pointerVersion());
  }

  private static GameplayAdmissionPointerMutation pointerMutation(
      long gameInstanceId, boolean publicProductionRealm, Long expectedPointerVersion) {
    return new GameplayAdmissionPointerMutation(
        "demo",
        "Demo World",
        "production",
        "Live Realm",
        7L,
        gameInstanceId,
        true,
        publicProductionRealm,
        false,
        "SHARED",
        "ALLOW_NEW",
        "test",
        "catalog revision test",
        "catalog-revision-test-" + expectedPointerVersion,
        expectedPointerVersion,
        null);
  }

  @Test
  void getAdmissionPointerRuntimeFailureReturnsInternalErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    GameplayAdmissionPointerAuthorityService pointerAuthorityService =
        Mockito.mock(GameplayAdmissionPointerAuthorityService.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Mockito.when(pointerAuthorityService.findPointer(7L, "demo", "production"))
        .thenThrow(new IllegalStateException("pointer store unavailable"));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            pointerAuthorityService,
            TestGameplayWorldCatalogs.fromProperties(new GameplayCatalogProperties()),
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<GetAdmissionPointerResponse> ref = new AtomicReference<>();
    service.getAdmissionPointer(
        GetAdmissionPointerRequest.newBuilder()
            .setTenantId("7")
            .setWorldSlug("demo")
            .setRealmSlug("production")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(GetAdmissionPointerResponse value) {
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
    assertEquals("Internal error", ref.get().getError().getMessage());
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
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenReturn(
            new GameInstanceDto(
                1L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 42L, "RUNNING"));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
                        && request.gameTemplateId().equals(7L)
                        && request.controlPlaneRequestId().equals("cp-1")
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
        newService(
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
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenReturn(
            new GameInstanceDto(
                1L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 42L, "RUNNING"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenReturn(
            new GameInstanceDto(
                1L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 42L, "RUNNING"));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
        .thenReturn(
            new GameInstanceDto(
                88L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 42L, "RUNNING"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
        .thenReturn(
            new GameInstanceDto(
                88L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 42L, "RUNNING"));
    Mockito.doThrow(new IllegalStateException("Failed to stop old session"))
        .when(gameInstanceService)
        .stopSession(77L);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
        .thenReturn(
            new GameInstanceDto(
                88L, 1L, "11", null, 7L, "ld-1", 11L, 77L, 77L, "genrev-11", 42L, "RUNNING"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
  void startSessionRuntimeFailureReturnsInBandInternalError() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenThrow(new IllegalStateException("Failed to start session"));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> responseRef = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<StartSessionResponse>() {
          @Override
          public void onNext(StartSessionResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("INTERNAL", responseRef.get().getError().getCode());
    assertEquals("Internal error", responseRef.get().getError().getMessage());
  }

  @Test
  void startSessionLifecycleConflictReturnsStructuredErrorDetail() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    SessionContext.setContext("42", List.of(), Map.of("1", List.of("tenantAdmin")));
    Mockito.when(
            gameInstanceService.startSession(
                Mockito.any(net.firedevops.firemud.gamesession.dto.StartSessionRequest.class),
                Mockito.eq(false)))
        .thenThrow(
            new LifecycleOutcomeException(
                "WORLD_TERMINATION_IN_PROGRESS", "replaced session is already terminating"));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StartSessionResponse> responseRef = new AtomicReference<>();
    service.startSession(
        StartSessionRequest.newBuilder()
            .setTenantId("1")
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-lifecycle")
            .setOwnerAccountId("42")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(StartSessionResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("WORLD_TERMINATION_IN_PROGRESS", responseRef.get().getError().getCode());
    assertEquals(
        "replaced session is already terminating", responseRef.get().getError().getMessage());
  }

  @Test
  void startSessionRejectsMalformedTenantId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
            .setTenantId("abc")
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be a number", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceService, Mockito.never())
        .startSession(Mockito.any(), Mockito.anyBoolean());
  }

  @Test
  void startSessionRejectsZeroTenantId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
            .setTenantId("0")
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceService, Mockito.never())
        .startSession(Mockito.any(), Mockito.anyBoolean());
  }

  @Test
  void startSessionRejectsZeroOwnerAccountId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
            .setOwnerAccountId("0")
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

    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("ownerAccountId must be positive", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceService, Mockito.never())
        .startSession(Mockito.any(), Mockito.anyBoolean());
  }

  @Test
  void stopSessionRuntimeFailureReturnsInBandInternalError() {
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.doThrow(new IllegalStateException("Failed to stop session"))
        .when(gameInstanceService)
        .stopSession(7L);
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StopSessionResponse> responseRef = new AtomicReference<>();
    service.stopSession(
        StopSessionRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<StopSessionResponse>() {
          @Override
          public void onNext(StopSessionResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(responseRef.get().getSuccess());
    assertEquals("INTERNAL", responseRef.get().getError().getCode());
    assertEquals("Internal error", responseRef.get().getError().getMessage());
  }

  @Test
  void stopSessionLifecycleConflictReturnsStructuredErrorDetail() {
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.doThrow(
            new LifecycleOutcomeException(
                "WORLD_INSTANCE_LIFECYCLE_NOT_ACTIVE", "instance is not ACTIVE"))
        .when(gameInstanceService)
        .stopSession(7L);
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<StopSessionResponse> responseRef = new AtomicReference<>();
    service.stopSession(
        StopSessionRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(StopSessionResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(responseRef.get().getSuccess());
    assertEquals("WORLD_INSTANCE_LIFECYCLE_NOT_ACTIVE", responseRef.get().getError().getCode());
    assertEquals("instance is not ACTIVE", responseRef.get().getError().getMessage());
  }

  @Test
  void stopSessionRejectsMalformedSessionId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
        StopSessionRequest.newBuilder().setSessionId("bad").build(),
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

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
    assertEquals("sessionId must be numeric", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceRepository, Mockito.never()).findById(Mockito.anyLong());
    Mockito.verify(gameInstanceService, Mockito.never()).stopSession(Mockito.anyLong());
  }

  @Test
  void restartSessionRejectsMalformedSessionId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
        RestartSessionRequest.newBuilder().setSessionId("bad").build(),
        new StreamObserver<>() {
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

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
    assertEquals("sessionId must be numeric", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceRepository, Mockito.never()).findById(Mockito.anyLong());
    Mockito.verify(gameInstanceService, Mockito.never()).restartSession(Mockito.anyLong());
  }

  @Test
  void restartSessionRuntimeFailureReturnsInBandInternalError() {
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.when(gameInstanceService.restartSession(7L))
        .thenThrow(new IllegalStateException("Failed to restart session"));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<RestartSessionResponse> responseRef = new AtomicReference<>();
    service.restartSession(
        RestartSessionRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<RestartSessionResponse>() {
          @Override
          public void onNext(RestartSessionResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(responseRef.get().getSuccess());
    assertEquals("INTERNAL", responseRef.get().getError().getCode());
    assertEquals("Internal error", responseRef.get().getError().getMessage());
  }

  @Test
  void restartSessionLifecycleConflictReturnsStructuredErrorDetail() {
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.when(gameInstanceService.restartSession(7L))
        .thenThrow(
            new LifecycleOutcomeException(
                "WORLD_TERMINATION_IN_PROGRESS", "session termination is already in progress"));
    GameSessionGrpcService service =
        newService(
            pingService,
            gameInstanceService,
            featureFlagService,
            textCommandInterpreter,
            gameInstanceRepository,
            tickService,
            meterRegistry,
            ipLimiter);

    AtomicReference<RestartSessionResponse> responseRef = new AtomicReference<>();
    service.restartSession(
        RestartSessionRequest.newBuilder().setSessionId("7").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(RestartSessionResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertFalse(responseRef.get().getSuccess());
    assertEquals("WORLD_TERMINATION_IN_PROGRESS", responseRef.get().getError().getCode());
    assertEquals(
        "session termination is already in progress", responseRef.get().getError().getMessage());
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
        newService(
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
        newService(
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
  void enqueueCommandRejectsMalformedSessionId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
            .setSessionId("bad")
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
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("sessionId must be numeric", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceRepository, Mockito.never()).findById(Mockito.anyLong());
    Mockito.verifyNoInteractions(textCommandInterpreter);
  }

  @Test
  void queryStateMissingSessionReturnsNotFoundErrorDetail() {
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.when(tickService.queryState(7L))
        .thenThrow(new IllegalArgumentException("No session context found for sessionId=7"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
    assertEquals("No session context found for sessionId=7", ref.get().getError().getMessage());
  }

  @Test
  void queryStateRejectsMalformedSessionId() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
        QueryStateRequest.newBuilder().setSessionId("bad").build(),
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

    assertEquals("NOT_FOUND", ref.get().getError().getCode());
    assertEquals("sessionId must be numeric", ref.get().getError().getMessage());
    Mockito.verify(gameInstanceRepository, Mockito.never()).findById(Mockito.anyLong());
    Mockito.verifyNoInteractions(tickService);
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.when(tickService.queryState(7L)).thenThrow(new IllegalStateException("boom"));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    Mockito.doThrow(new IllegalStateException("boom"))
        .when(featureFlagService)
        .toggleFlag(
            Mockito.any(net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest.class));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
  void toggleFeatureFlagRejectsZeroTenantIdBeforeDispatch() {
    PingService pingService = Mockito.mock(PingService.class);
    GameInstanceService gameInstanceService = Mockito.mock(GameInstanceService.class);
    FeatureFlagService featureFlagService = Mockito.mock(FeatureFlagService.class);
    TextCommandInterpreter textCommandInterpreter = Mockito.mock(TextCommandInterpreter.class);
    GameInstanceRepository gameInstanceRepository = Mockito.mock(GameInstanceRepository.class);
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SessionContext.setContext("42", List.of(), Map.of("9", List.of("tenantAdmin")));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
            .setTenantId("0")
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
    assertEquals("INVALID_ARGUMENT", ref.get().getError().getCode());
    assertEquals("tenantId must be positive", ref.get().getError().getMessage());
    Mockito.verifyNoInteractions(featureFlagService);
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
        newService(
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
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
    SessionContext.setContext("99", List.of(), Map.of("1", List.of("tenantAdmin")));
    GameSessionGrpcService service =
        newService(
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
            .setGameTemplateId("7")
            .setControlPlaneRequestId("cp-1")
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
        newService(
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
        newService(
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

  @Test
  void stopSessionRejectsMalformedCurrentAccountClaim() {
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
    SessionContext.setContext("not-a-number", List.of(), Map.of());
    TickService tickService = Mockito.mock(TickService.class);
    IpConnectionLimiter ipLimiter = Mockito.mock(IpConnectionLimiter.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GameSessionGrpcService service =
        newService(
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
