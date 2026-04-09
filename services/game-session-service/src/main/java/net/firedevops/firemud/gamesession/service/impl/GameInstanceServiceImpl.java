package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Default implementation of {@link GameInstanceService}. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed externally")
@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public class GameInstanceServiceImpl implements GameInstanceService {
  private static final Logger logger = LoggingUtil.getLogger(GameInstanceServiceImpl.class);

  private final GameInstanceRepository repository;
  private final GameInstanceMapper mapper;
  private final SessionStateService sessionStateService;
  private final GameLogicClient gameLogicClient;
  private final WorldManagementClient worldManagementClient;
  private final EntityManagementClient entityManagementClient;
  private final SagaRunner sagaRunner;
  private final MeterRegistry meterRegistry;
  private final DevIsolatedProperties devIsolatedProperties;

  @Autowired
  public GameInstanceServiceImpl(
      GameInstanceRepository repository,
      GameInstanceMapper mapper,
      SessionStateService sessionStateService,
      GameLogicClient gameLogicClient,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      @Nullable SagaRunner sagaRunner,
      MeterRegistry meterRegistry,
      DevIsolatedProperties devIsolatedProperties) {
    this.repository = repository;
    this.mapper = mapper;
    this.sessionStateService = sessionStateService;
    this.gameLogicClient = gameLogicClient;
    this.worldManagementClient = worldManagementClient;
    this.entityManagementClient = entityManagementClient;
    this.sagaRunner = sagaRunner;
    this.meterRegistry = meterRegistry;
    this.devIsolatedProperties = devIsolatedProperties;
  }

  // Constructor used in unit tests
  public GameInstanceServiceImpl(
      GameInstanceRepository repository,
      GameInstanceMapper mapper,
      SessionStateService sessionStateService) {
    this(
        repository,
        mapper,
        sessionStateService,
        null,
        null,
        null,
        null,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        new DevIsolatedProperties(false));
  }

  @Override
  @Timed(value = "gamesession.start")
  @Transactional
  public GameInstanceDto startSession(StartSessionRequest request, boolean replaceExistingFirst) {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info(
          "Dev-isolated mode enabled; acknowledging start for tenant {} version {} patch {}",
          request.tenantId(),
          request.runtimeVersion(),
          request.scriptPatchVersion());
      return new GameInstanceDto(
          -1L,
          request.tenantId(),
          request.runtimeVersion(),
          request.scriptPatchVersion(),
          request.ownerAccountId(),
          "RUNNING");
    }

    logger.info(
        "Starting game session for tenant {} version {} patch {}",
        request.tenantId(),
        request.runtimeVersion(),
        request.scriptPatchVersion());
    GameInstance existingRunning = null;
    GameInstanceDto existingRunningState = null;
    GameInstance instance = null;
    try {
      if (replaceExistingFirst) {
        existingRunning =
            repository
                .findFirstByTenantIdAndOwnerAccountIdAndStatus(
                    request.tenantId(), request.ownerAccountId(), "RUNNING")
                .orElse(null);
        if (existingRunning != null) {
          GameInstance existingToStop = existingRunning;
          GameInstanceDto existingToRestore = existingRunningState;
          logger.info(
              "Stopping existing session {} for tenant {} owner {}",
              existingRunning.getId(),
              existingRunning.getTenantId(),
              existingRunning.getOwnerAccountId());
          existingRunningState = snapshot(existingRunning);
          existingRunning.setStatus("STOPPED");
          repository.save(existingRunning);
          runBeforeCommit(
              "delete stopped session state",
              () ->
                  sessionStateService.deleteState(
                      existingToStop.getTenantId(), existingToStop.getId()),
              () -> sessionStateService.saveState(existingToRestore));
        }
      }
      instance = new GameInstance();
      instance.setTenantId(request.tenantId());
      instance.setRuntimeVersion(request.runtimeVersion());
      instance.setScriptPatchVersion(request.scriptPatchVersion());
      instance.setOwnerAccountId(request.ownerAccountId());
      instance.setStatus("RUNNING");
      instance = repository.save(instance);
      GameInstanceDto dto = mapper.toDto(instance);
      if (gameLogicClient != null
          && worldManagementClient != null
          && entityManagementClient != null
          && sagaRunner != null) {
        var saga =
            new SagaBuilder("startSession")
                .step("checkWorldService", () -> worldManagementClient.ping())
                .step("checkEntityService", () -> entityManagementClient.ping())
                .step("notifyGameLogic", () -> gameLogicClient.ping())
                .build();
        runBeforeCommit(
            "validate session dependencies",
            () -> {
              try {
                sagaRunner.run(saga);
              } catch (SagaException e) {
                logger.error("Saga failed during session start", e);
                throw new IllegalStateException("Failed to start session", e);
              }
            },
            null);
      }

      runBeforeCommit(
          "save started session state",
          () -> sessionStateService.saveState(dto),
          () -> sessionStateService.deleteState(dto.tenantId(), dto.id()));
      meterRegistry
          .counter(
              "game_sessions_started_total",
              "script_patch_version",
              request.scriptPatchVersion() == null ? "none" : request.scriptPatchVersion())
          .increment();
      return dto;
    } catch (RuntimeException ex) {
      if (instance != null && instance.getId() != null) {
        repository.delete(instance);
      }
      restoreExistingSession(existingRunning, existingRunningState);
      throw ex;
    }
  }

  @Override
  @Timed(value = "gamesession.stop")
  @Transactional
  public GameInstanceDto stopSession(long sessionId) {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; acknowledging stop for session {}", sessionId);
      return new GameInstanceDto(sessionId, 0L, "dev-isolated", null, 0L, "STOPPED");
    }

    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    GameInstanceDto runningState = snapshot(instance);
    try {
      instance.setStatus("STOPPED");
      GameInstance saved = repository.save(instance);
      if (gameLogicClient != null
          && worldManagementClient != null
          && entityManagementClient != null
          && sagaRunner != null) {
        var saga =
            new SagaBuilder("stopSession")
                .step("notifyGameLogic", () -> gameLogicClient.ping())
                .step("flushWorldService", () -> worldManagementClient.ping())
                .step("flushEntityService", () -> entityManagementClient.ping())
                .build();
        runBeforeCommit(
            "validate session stop dependencies",
            () -> {
              try {
                sagaRunner.run(saga);
              } catch (SagaException e) {
                logger.error("Saga failed during session stop", e);
                throw new IllegalStateException("Failed to stop session", e);
              }
            },
            null);
      }
      runBeforeCommit(
          "delete stopped session state",
          () -> sessionStateService.deleteState(instance.getTenantId(), instance.getId()),
          () -> sessionStateService.saveState(runningState));
      return mapper.toDto(saved);
    } catch (RuntimeException ex) {
      restoreSessionSnapshot(instance, runningState);
      throw ex;
    }
  }

  @Override
  @Timed(value = "gamesession.restart")
  @Transactional
  public GameInstanceDto restartSession(long sessionId) {
    if (devIsolatedProperties.isDevIsolated()) {
      logger.info("Dev-isolated mode enabled; acknowledging restart for session {}", sessionId);
      return new GameInstanceDto(sessionId, 0L, "dev-isolated", null, 0L, "RUNNING");
    }

    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    GameInstanceDto previousState = snapshot(instance);
    try {
      instance.setStatus("RUNNING");
      GameInstance saved = repository.save(instance);
      GameInstanceDto dto = mapper.toDto(saved);
      runBeforeCommit(
          "save restarted session state",
          () -> sessionStateService.saveState(dto),
          () -> sessionStateService.deleteState(dto.tenantId(), dto.id()));
      return dto;
    } catch (RuntimeException ex) {
      restoreSessionSnapshot(instance, previousState);
      throw ex;
    }
  }

  private GameInstanceDto snapshot(GameInstance instance) {
    return new GameInstanceDto(
        instance.getId(),
        instance.getTenantId(),
        instance.getRuntimeVersion(),
        instance.getScriptPatchVersion(),
        instance.getOwnerAccountId(),
        instance.getStatus());
  }

  private void runBeforeCommit(
      String actionName, Runnable action, @Nullable Runnable rollbackAction) {
    runOrThrow(actionName, action);
    if (!TransactionSynchronizationManager.isSynchronizationActive() || rollbackAction == null) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
              runRollbackSafely(actionName, rollbackAction);
            }
          }
        });
  }

  private void runOrThrow(String actionName, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      logger.warn("Failed to {}", actionName, ex);
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      }
      throw ex;
    }
  }

  private void runRollbackSafely(String actionName, Runnable rollbackAction) {
    try {
      rollbackAction.run();
    } catch (RuntimeException ex) {
      logger.warn("Failed to roll back {}", actionName, ex);
    }
  }

  private void restoreExistingSession(
      @Nullable GameInstance existingRunning, @Nullable GameInstanceDto existingRunningState) {
    if (existingRunning != null && existingRunningState != null) {
      restoreSessionSnapshot(existingRunning, existingRunningState);
    }
  }

  private void restoreSessionSnapshot(GameInstance instance, GameInstanceDto snapshot) {
    instance.setStatus(snapshot.status());
    instance.setRuntimeVersion(snapshot.runtimeVersion());
    instance.setScriptPatchVersion(snapshot.scriptPatchVersion());
    instance.setOwnerAccountId(snapshot.ownerAccountId());
    instance.setTenantId(snapshot.tenantId());
    repository.save(instance);
  }
}
