package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpreter;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.AccountPresenceQueryService;
import net.firedevops.firemud.gamesession.service.FeatureFlagService;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.IpConnectionLimiter;
import net.firedevops.firemud.gamesession.service.PingService;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;
import net.firedevops.firemud.gamesession.service.TickService;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.v1.EnqueueCommandRequest;
import net.firedevops.firemud.gamesession.v1.EnqueueCommandResponse;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetAdmissionPointerRequest;
import net.firedevops.firemud.gamesession.v1.GetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.GetTickStatusRequest;
import net.firedevops.firemud.gamesession.v1.GetTickStatusResponse;
import net.firedevops.firemud.gamesession.v1.ListGameplayRealmsRequest;
import net.firedevops.firemud.gamesession.v1.ListGameplayRealmsResponse;
import net.firedevops.firemud.gamesession.v1.ListGameplayWorldsRequest;
import net.firedevops.firemud.gamesession.v1.ListGameplayWorldsResponse;
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
import net.firedevops.firemud.gamesession.v1.StartSessionResponse;
import net.firedevops.firemud.gamesession.v1.StopSessionRequest;
import net.firedevops.firemud.gamesession.v1.StopSessionResponse;
import net.firedevops.firemud.gamesession.v1.TickStatus;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagRequest;
import net.firedevops.firemud.gamesession.v1.ToggleFeatureFlagResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC endpoints for the Game Session Service. */
@GrpcService
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected service collaborators are framework-managed and retained internally")
public final class GameSessionGrpcService
    extends GameSessionServiceGrpc.GameSessionServiceImplBase {
  private static final Logger LOG = LoggerFactory.getLogger(GameSessionGrpcService.class);
  private final PingService pingService;
  private final GameInstanceService gameInstanceService;
  private final FeatureFlagService featureFlagService;
  private final TextCommandInterpreter textCommandInterpreter;
  private final GameInstanceRepository gameInstanceRepository;
  private final AccountPresenceQueryService accountPresenceQueryService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameplayWorldCatalog gameplayWorldCatalog;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "TickService is injected and not exposed")
  private final TickService tickService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  private final IpConnectionLimiter ipConnectionLimiter;

  @Autowired
  public GameSessionGrpcService(
      PingService pingService,
      GameInstanceService gameInstanceService,
      FeatureFlagService featureFlagService,
      TextCommandInterpreter textCommandInterpreter,
      GameInstanceRepository gameInstanceRepository,
      AccountPresenceQueryService accountPresenceQueryService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameplayWorldCatalog gameplayWorldCatalog,
      TickService tickService,
      MeterRegistry meterRegistry,
      IpConnectionLimiter ipConnectionLimiter) {
    this.pingService = pingService;
    this.gameInstanceService = gameInstanceService;
    this.featureFlagService = featureFlagService;
    this.textCommandInterpreter = textCommandInterpreter;
    this.gameInstanceRepository = gameInstanceRepository;
    this.accountPresenceQueryService = accountPresenceQueryService;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
    this.gameplayWorldCatalog = gameplayWorldCatalog;
    this.tickService = tickService;
    this.meterRegistry = meterRegistry;
    this.ipConnectionLimiter = ipConnectionLimiter;
  }

  @Override
  @Timed(value = "gamesessionGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    String msg = pingService.ping();
    PingResponse response =
        PingResponse.newBuilder().setMessage(msg).setError(GrpcAppErrors.ok(msg)).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.startSession")
  public void startSession(
      net.firedevops.firemud.gamesession.v1.StartSessionRequest request,
      StreamObserver<StartSessionResponse> responseObserver) {
    try {
      String clientIp = request.getClientIp();
      long tenantId =
          ControlPlaneRequestParser.parsePositiveLong(request.getTenantId(), "tenantId");
      long ownerAccountId = parseOwnerAccountId(request.getOwnerAccountId());
      long gameTemplateId =
          ControlPlaneRequestParser.parsePositiveLong(
              request.getGameTemplateId(), "gameTemplateId");
      requireTenantAndOwnerAccess(tenantId, ownerAccountId);
      GameInstance existingRunningSession =
          gameInstanceRepository
              .findFirstByTenantIdAndOwnerAccountIdAndStatus(tenantId, ownerAccountId, "RUNNING")
              .orElse(null);
      if (clientIp != null
          && !clientIp.isBlank()
          && !ipConnectionLimiter.canAccept(
              clientIp, existingRunningSession != null ? existingRunningSession.getId() : null)) {
        StartSessionResponse response =
            StartSessionResponse.newBuilder()
                .setError(
                    GrpcAppErrors.error(
                        meterRegistry, "CONNECTION_LIMIT", "Too many connections from IP"))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
        return;
      }
      StartSessionRequest dto =
          new StartSessionRequest(
              tenantId, gameTemplateId, request.getControlPlaneRequestId(), ownerAccountId);
      GameInstanceDto instance = gameInstanceService.startSession(dto, false);
      boolean transferredRegistration = false;
      if (clientIp != null && !clientIp.isBlank()) {
        transferredRegistration =
            existingRunningSession != null
                && ipConnectionLimiter.transferRegistration(
                    clientIp, existingRunningSession.getId(), instance.id());
        if (!transferredRegistration && !ipConnectionLimiter.tryRegister(clientIp, instance.id())) {
          gameInstanceService.stopSession(instance.id());
          StartSessionResponse response =
              StartSessionResponse.newBuilder()
                  .setError(
                      GrpcAppErrors.error(
                          meterRegistry, "CONNECTION_LIMIT", "Too many connections from IP"))
                  .build();
          responseObserver.onNext(response);
          responseObserver.onCompleted();
          return;
        }
      }
      if (existingRunningSession != null) {
        if (!transferredRegistration) {
          ipConnectionLimiter.release(existingRunningSession.getId());
        }
        try {
          gameInstanceService.stopSession(existingRunningSession.getId());
        } catch (IllegalStateException ex) {
          LOG.warn(
              "Replacement session {} admitted, but teardown of previous session {} failed",
              instance.id(),
              existingRunningSession.getId(),
              ex);
        }
      }
      StartSessionResponse response =
          StartSessionResponse.newBuilder().setSessionId(instance.id().toString()).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      StartSessionResponse response =
          StartSessionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      StartSessionResponse response =
          StartSessionResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      onInternalFailure(responseObserver, "startSession", ex);
    }
  }

  private long parseOwnerAccountId(String ownerAccountIdText) {
    return ControlPlaneRequestParser.parsePositiveLong(ownerAccountIdText, "ownerAccountId");
  }

  private List<Long> parseAccountIds(List<String> accountIds) {
    return accountIds.stream()
        .map(accountId -> ControlPlaneRequestParser.parsePositiveLong(accountId, "accountId"))
        .toList();
  }

  private void requireTenantAndOwnerAccess(long tenantId, long ownerAccountId) {
    requireTenantAccess(tenantId);
    if (isCurrentAccount(ownerAccountId)) {
      return;
    }
    throw new AuthorizationException("Owner access required");
  }

  @Override
  @Timed(value = "gamesessionGrpc.stopSession")
  public void stopSession(
      StopSessionRequest request, StreamObserver<StopSessionResponse> responseObserver) {
    try {
      long sessionId = SessionIdParsing.require(request.getSessionId());
      requireInstanceAccess(sessionId);
      gameInstanceService.stopSession(sessionId);
      ipConnectionLimiter.release(sessionId);
      StopSessionResponse response = StopSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      StopSessionResponse response =
          StopSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      StopSessionResponse response =
          StopSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      onInternalFailure(responseObserver, "stopSession", ex);
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.restartSession")
  public void restartSession(
      RestartSessionRequest request, StreamObserver<RestartSessionResponse> responseObserver) {
    try {
      long sessionId = SessionIdParsing.require(request.getSessionId());
      requireInstanceAccess(sessionId);
      gameInstanceService.restartSession(sessionId);
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      RestartSessionResponse response =
          RestartSessionResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalStateException ex) {
      onInternalFailure(responseObserver, "restartSession", ex);
    }
  }

  private void onInternalFailure(
      StreamObserver<?> responseObserver, String operation, IllegalStateException cause) {
    ErrorDetail detail = GrpcAppErrors.internal(meterRegistry, LOG, operation, cause);
    responseObserver.onError(
        Status.INTERNAL.withDescription(detail.getMessage()).asRuntimeException());
  }

  @Override
  @Timed(value = "gamesessionGrpc.enqueueCommand")
  public void enqueueCommand(
      EnqueueCommandRequest request, StreamObserver<EnqueueCommandResponse> responseObserver) {
    try {
      long sessionId = SessionIdParsing.require(request.getSessionId());
      requireInstanceAccess(sessionId);
      TextCommandInterpretationResult interpretation =
          textCommandInterpreter.interpret(
              request.getSessionId(), request.getCommand(), request.getRequiresSoloTick());
      var commandResult = interpretation.commandResult();
      EnqueueCommandResponse.Builder builder =
          EnqueueCommandResponse.newBuilder().setAccepted(commandResult.accepted());
      if (commandResult.commandId() != null) {
        builder.setCommandId(commandResult.commandId());
      }
      if (commandResult.hasError()) {
        builder.setError(
            GrpcAppErrors.error(
                meterRegistry, commandResult.errorCode(), commandResult.errorMessage()));
      }
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      EnqueueCommandResponse response =
          EnqueueCommandResponse.newBuilder()
              .setAccepted(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      EnqueueCommandResponse response =
          EnqueueCommandResponse.newBuilder()
              .setAccepted(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      EnqueueCommandResponse response =
          EnqueueCommandResponse.newBuilder()
              .setAccepted(false)
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "EnqueueCommand", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.queryState")
  public void queryState(
      QueryStateRequest request, StreamObserver<QueryStateResponse> responseObserver) {
    try {
      long sessionId = SessionIdParsing.require(request.getSessionId());
      requireInstanceAccess(sessionId);
      String state = tickService.queryState(sessionId);
      QueryStateResponse response = QueryStateResponse.newBuilder().setStateJson(state).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryStateResponse response =
          QueryStateResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "NOT_FOUND", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      QueryStateResponse response =
          QueryStateResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      QueryStateResponse response =
          QueryStateResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "QueryState", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.queryAccountPresence")
  public void queryAccountPresence(
      QueryAccountPresenceRequest request,
      StreamObserver<QueryAccountPresenceResponse> responseObserver) {
    try {
      long tenantId =
          ControlPlaneRequestParser.parsePositiveLong(request.getTenantId(), "tenantId");
      long viewerAccountId = parseOwnerAccountId(request.getViewerAccountId());
      requireTenantOrCurrentAccountAccess(tenantId, viewerAccountId);
      if (request.getAccountIdsCount() > 100) {
        throw new IllegalArgumentException("accountIds must contain at most 100 entries");
      }
      List<Long> accountIds = parseAccountIds(request.getAccountIdsList());
      QueryAccountPresenceResponse.Builder builder = QueryAccountPresenceResponse.newBuilder();
      for (var snapshot :
          accountPresenceQueryService.queryAccountPresence(tenantId, viewerAccountId, accountIds)) {
        AccountPresenceEntry.Builder entry =
            AccountPresenceEntry.newBuilder()
                .setAccountId(Long.toString(snapshot.accountId()))
                .setOnline(snapshot.online());
        if (snapshot.gameInstanceId() != null) {
          entry.setGameInstanceId(Long.toString(snapshot.gameInstanceId()));
        }
        if (snapshot.worldSlug() != null && !snapshot.worldSlug().isBlank()) {
          entry.setWorldSlug(snapshot.worldSlug());
        }
        if (snapshot.worldDisplayName() != null && !snapshot.worldDisplayName().isBlank()) {
          entry.setWorldDisplayName(snapshot.worldDisplayName());
        }
        if (snapshot.realmSlug() != null && !snapshot.realmSlug().isBlank()) {
          entry.setRealmSlug(snapshot.realmSlug());
        }
        if (snapshot.realmDisplayName() != null && !snapshot.realmDisplayName().isBlank()) {
          entry.setRealmDisplayName(snapshot.realmDisplayName());
        }
        if (snapshot.pointerVersion() != null && snapshot.pointerVersion() > 0L) {
          entry.setPointerVersion(snapshot.pointerVersion());
        }
        if (snapshot.playableStateScope() != null && !snapshot.playableStateScope().isBlank()) {
          entry.setPlayableStateScope(mapPlayableStateScope(snapshot.playableStateScope()));
        }
        if (snapshot.characterId() != null) {
          entry.setCharacterId(Long.toString(snapshot.characterId()));
        }
        if (snapshot.characterName() != null && !snapshot.characterName().isBlank()) {
          entry.setCharacterName(snapshot.characterName());
        }
        if (snapshot.activityState() != null) {
          entry.setActivityState(mapActivityState(snapshot.activityState().name()));
        }
        if (snapshot.lastSeenAt() != null) {
          entry.setLastSeenAtMs(snapshot.lastSeenAt().toEpochMilli());
        }
        if (snapshot.recentDisposition() != null) {
          entry.setRecentDisposition(mapRecentDisposition(snapshot.recentDisposition().name()));
        }
        builder.addPresences(entry.build());
      }
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      QueryAccountPresenceResponse response =
          QueryAccountPresenceResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      QueryAccountPresenceResponse response =
          QueryAccountPresenceResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      QueryAccountPresenceResponse response =
          QueryAccountPresenceResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "QueryAccountPresence", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.listGameplayWorlds")
  public void listGameplayWorlds(
      ListGameplayWorldsRequest request,
      StreamObserver<ListGameplayWorldsResponse> responseObserver) {
    ListGameplayWorldsResponse response =
        ListGameplayWorldsResponse.newBuilder()
            .addAllWorlds(
                gameplayWorldCatalog.visibleWorlds().stream()
                    .map(
                        world ->
                            net.firedevops.firemud.gamesession.v1.GameplayWorld.newBuilder()
                                .setWorldSlug(world.slug())
                                .setDisplayName(world.displayName())
                                .build())
                    .toList())
            .build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamesessionGrpc.listGameplayRealms")
  public void listGameplayRealms(
      ListGameplayRealmsRequest request,
      StreamObserver<ListGameplayRealmsResponse> responseObserver) {
    try {
      WorldView world =
          gameplayWorldCatalog
              .resolveWorld(request.getWorldSlug())
              .orElseThrow(() -> new IllegalArgumentException("Unknown gameplay world selection"));
      ListGameplayRealmsResponse response =
          ListGameplayRealmsResponse.newBuilder()
              .addAllRealms(
                  gameplayWorldCatalog.visibleRealms(world).stream()
                      .map(realm -> toGameplayRealm(world.slug(), realm))
                      .toList())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ListGameplayRealmsResponse response =
          ListGameplayRealmsResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.getAdmissionPointer")
  public void getAdmissionPointer(
      GetAdmissionPointerRequest request,
      StreamObserver<GetAdmissionPointerResponse> responseObserver) {
    try {
      long tenantId =
          ControlPlaneRequestParser.parsePositiveLong(request.getTenantId(), "tenantId");
      GameplayAdmissionPointerSnapshot realm =
          gameplayAdmissionPointerAuthorityService
              .findPointer(tenantId, request.getWorldSlug(), request.getRealmSlug())
              .orElseThrow(() -> new IllegalArgumentException("Unknown gameplay realm selection"));
      GetAdmissionPointerResponse response =
          GetAdmissionPointerResponse.newBuilder()
              .setAdmissionPointer(
                  net.firedevops.firemud.gamesession.v1.GameplayAdmissionPointer.newBuilder()
                      .setWorldSlug(realm.worldSlug())
                      .setWorldDisplayName(realm.worldDisplayName())
                      .setRealmSlug(realm.realmSlug())
                      .setRealmDisplayName(realm.realmDisplayName())
                      .setTenantId(Long.toString(realm.tenantId()))
                      .setGameInstanceId(Long.toString(realm.gameInstanceId()))
                      .setPointerVersion(realm.pointerVersion())
                      .setRequiresCharacterSelection(realm.requiresCharacterSelection())
                      .setVisible(realm.visible())
                      .setPublicProductionRealm(realm.publicProductionRealm())
                      .setStateScope(realm.stateScope())
                      .setCharacterCreationPolicy(realm.characterCreationPolicy())
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      GetAdmissionPointerResponse response =
          GetAdmissionPointerResponse.newBuilder()
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      GetAdmissionPointerResponse response =
          GetAdmissionPointerResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "GetAdmissionPointer", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.toggleFeatureFlag")
  public void toggleFeatureFlag(
      ToggleFeatureFlagRequest request,
      StreamObserver<ToggleFeatureFlagResponse> responseObserver) {
    try {
      long tenantId =
          ControlPlaneRequestParser.parsePositiveLong(request.getTenantId(), "tenantId");
      requireTenantAccess(tenantId);
      featureFlagService.toggleFlag(
          new net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest(
              tenantId, request.getName(), request.getEnabled()));
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "INVALID_ARGUMENT", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ToggleFeatureFlagResponse response =
          ToggleFeatureFlagResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "ToggleFeatureFlag", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.pauseTicks")
  public void pauseTicks(
      PauseTicksRequest request, StreamObserver<PauseTicksResponse> responseObserver) {
    try {
      requireGlobalPrivilegedRole();
      tickService.pauseTicks(request.getReason());
      PauseTicksResponse response = PauseTicksResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      PauseTicksResponse response =
          PauseTicksResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      PauseTicksResponse response =
          PauseTicksResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "PauseTicks", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.resumeTicks")
  public void resumeTicks(
      ResumeTicksRequest request, StreamObserver<ResumeTicksResponse> responseObserver) {
    try {
      requireGlobalPrivilegedRole();
      tickService.resumeTicks(request.getReason());
      ResumeTicksResponse response = ResumeTicksResponse.newBuilder().setSuccess(true).build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (AuthorizationException ex) {
      ResumeTicksResponse response =
          ResumeTicksResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.error(meterRegistry, "PERMISSION_DENIED", ex.getMessage()))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception ex) {
      ResumeTicksResponse response =
          ResumeTicksResponse.newBuilder()
              .setSuccess(false)
              .setError(GrpcAppErrors.internal(meterRegistry, LOG, "ResumeTicks", ex))
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamesessionGrpc.getTickStatus")
  public void getTickStatus(
      GetTickStatusRequest request, StreamObserver<GetTickStatusResponse> responseObserver) {
    TickStatus status = tickService.getTickStatus();
    GetTickStatusResponse response = GetTickStatusResponse.newBuilder().setStatus(status).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  private void requireTenantAccess(long tenantId) {
    if (SessionContext.hasTenantAccess(tenantId)) {
      return;
    }
    throw new AuthorizationException("Tenant access required");
  }

  private void requireTenantOrCurrentAccountAccess(long tenantId, long accountId) {
    if (SessionContext.hasTenantAccess(tenantId) || isCurrentAccount(accountId)) {
      return;
    }
    throw new AuthorizationException("Account access required");
  }

  private void requireInstanceAccess(long sessionId) {
    GameInstance instance =
        gameInstanceRepository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    if (SessionContext.hasTenantAccess(instance.getTenantId()) || isCurrentAccount(instance)) {
      return;
    }
    throw new AuthorizationException("Session ownership required");
  }

  private void requireGlobalPrivilegedRole() {
    if (SessionContext.hasGlobalPrivilegedRole()) {
      return;
    }
    throw new AuthorizationException("Admin role required");
  }

  private boolean isCurrentAccount(GameInstance instance) {
    return isCurrentAccount(instance.getOwnerAccountId());
  }

  private boolean isCurrentAccount(long ownerAccountId) {
    return SessionContext.isCurrentAccount(ownerAccountId);
  }

  private net.firedevops.firemud.gamesession.v1.GameplayRealm toGameplayRealm(
      String worldSlug, RealmView realm) {
    return net.firedevops.firemud.gamesession.v1.GameplayRealm.newBuilder()
        .setWorldSlug(worldSlug)
        .setRealmSlug(realm.slug())
        .setDisplayName(realm.displayName())
        .setTenantId(Long.toString(realm.tenantId()))
        .setGameInstanceId(Long.toString(realm.gameInstanceId()))
        .setPointerVersion(realm.pointerVersion())
        .setRequiresCharacterSelection(realm.requiresCharacterSelection())
        .setVisible(realm.visible())
        .setPublicProductionRealm(realm.publicProductionRealm())
        .setStateScope(realm.stateScope())
        .setCharacterCreationPolicy(realm.characterCreationPolicy())
        .build();
  }

  private static final class AuthorizationException extends RuntimeException {
    private AuthorizationException(String message) {
      super(message);
    }
  }

  private AccountPresenceActivityState mapActivityState(String activityState) {
    return switch (activityState) {
      case "ACTIVE" -> AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_ACTIVE;
      case "AUTO_AFK" -> AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_AUTO_AFK;
      case "EXPLICIT_AFK" ->
          AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_EXPLICIT_AFK;
      default -> AccountPresenceActivityState.ACCOUNT_PRESENCE_ACTIVITY_STATE_UNSPECIFIED;
    };
  }

  private AccountRecentPresenceDisposition mapRecentDisposition(String disposition) {
    return switch (disposition) {
      case "TRANSPORT_LOSS" ->
          AccountRecentPresenceDisposition.ACCOUNT_RECENT_PRESENCE_DISPOSITION_TRANSPORT_LOSS;
      case "LOGOUT" -> AccountRecentPresenceDisposition.ACCOUNT_RECENT_PRESENCE_DISPOSITION_LOGOUT;
      case "TAKEOVER" ->
          AccountRecentPresenceDisposition.ACCOUNT_RECENT_PRESENCE_DISPOSITION_TAKEOVER;
      default -> AccountRecentPresenceDisposition.ACCOUNT_RECENT_PRESENCE_DISPOSITION_UNSPECIFIED;
    };
  }

  private net.firedevops.firemud.entitymanagement.v1.PlayableStateScope mapPlayableStateScope(
      String playableStateScope) {
    return switch (playableStateScope) {
      case "SHARED" ->
          net.firedevops.firemud.entitymanagement.v1.PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" ->
          net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
              .PLAYABLE_STATE_SCOPE_ISOLATED;
      default ->
          net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
              .PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }
}
