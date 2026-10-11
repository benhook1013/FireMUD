package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.ResolvedLaunchDescriptor;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.mapper.GameInstanceMapper;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.RunOwnedInitialLaunchResult;
import net.firedevops.firemud.gamesession.service.SessionStateService;
import net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.FailPreparedWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Default implementation of {@link GameInstanceService}. */
@Service
public class GameInstanceServiceImpl implements GameInstanceService {
  private static final Logger logger = LoggingUtil.getLogger(GameInstanceServiceImpl.class);
  private static final String STATUS_STARTING = "STARTING";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_STOPPING = "STOPPING";
  private static final String STATUS_STOPPED = "STOPPED";
  private static final String RUN_OWNED_LAUNCH_DIGEST_SCHEMA =
      "firemud.run-owned-initial-launch/v1";
  private static final String SUPPORTED_RELEASE_ATTESTATION_SCHEMA_VERSION = "v1";
  private static final String WORLD_ACTIVATION_AUTHORITY_UNAVAILABLE =
      "world activation authority unavailable";
  private static final String WORLD_FAIL_PREPARED_AUTHORITY_UNAVAILABLE =
      "world fail-prepared authority unavailable";
  private static final String WORLD_PREPARATION_AUTHORITY_UNAVAILABLE =
      "world preparation authority unavailable";
  private static final String WORLD_LIFECYCLE_AUTHORITY_UNAVAILABLE =
      "world lifecycle authority unavailable";
  private static final String WORLD_TERMINATION_AUTHORITY_UNAVAILABLE =
      "world termination authority unavailable";
  private static final String WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL =
      "WORLD_AUTHORITY_MALFORMED: response was null";

  private final GameInstanceRepository repository;
  private final GameInstanceMapper mapper;
  private final SessionStateService sessionStateService;
  private final GameDesignClient gameDesignClient;
  private final GameLogicClient gameLogicClient;
  private final WorldManagementClient worldManagementClient;
  private final EntityManagementClient entityManagementClient;
  private final SagaRunner sagaRunner;
  private final MeterRegistry meterRegistry;
  private final TransactionOperations transactionOperations;

  @Autowired
  public GameInstanceServiceImpl(
      GameInstanceRepository repository,
      GameInstanceMapper mapper,
      SessionStateService sessionStateService,
      GameDesignClient gameDesignClient,
      GameLogicClient gameLogicClient,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      @Nullable SagaRunner sagaRunner,
      MeterRegistry meterRegistry,
      PlatformTransactionManager transactionManager) {
    this(
        repository,
        mapper,
        sessionStateService,
        gameDesignClient,
        gameLogicClient,
        worldManagementClient,
        entityManagementClient,
        sagaRunner,
        meterRegistry,
        new TransactionTemplate(transactionManager));
  }

  GameInstanceServiceImpl(
      GameInstanceRepository repository,
      GameInstanceMapper mapper,
      SessionStateService sessionStateService,
      GameDesignClient gameDesignClient,
      GameLogicClient gameLogicClient,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      @Nullable SagaRunner sagaRunner,
      MeterRegistry meterRegistry,
      TransactionOperations transactionOperations) {
    this.repository = repository;
    this.mapper = mapper;
    this.sessionStateService = sessionStateService;
    this.gameDesignClient = gameDesignClient;
    this.gameLogicClient = gameLogicClient;
    this.worldManagementClient = worldManagementClient;
    this.entityManagementClient = entityManagementClient;
    this.sagaRunner = sagaRunner;
    this.meterRegistry = meterRegistry;
    this.transactionOperations = transactionOperations;
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
        null,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        immediateTransactionOperations());
  }

  @Override
  @Timed(value = "gamesession.start")
  public GameInstanceDto startSession(StartSessionRequest request, boolean replaceExistingFirst) {
    logger.info(
        "Starting game session for tenant {} template {} controlPlaneRequestId {}",
        request.tenantId(),
        request.gameTemplateId(),
        request.controlPlaneRequestId());

    ResolvedLaunchDescriptor resolvedLaunchDescriptor = preflightLaunch(request);

    StartSessionStage stage =
        inTransaction(
            () -> stageStartSession(request, resolvedLaunchDescriptor, replaceExistingFirst),
            "stage session start");
    GameInstanceDto runtimeState = withStatus(stage.startingState(), STATUS_STARTING);
    boolean newStateSaved = false;
    boolean oldWorldTerminationRequested = false;
    boolean oldWorldTerminationCompleted = false;
    boolean oldSessionStopped = false;
    boolean worldActivationMayHaveCommitted = false;
    PreparedWorldInstance preparedWorldInstance = null;
    GameInstanceDto finalized;
    try {
      validateStartDependencies();
      preparedWorldInstance =
          prepareWorldInstance(stage.startingState(), resolvedLaunchDescriptor, request);
      sessionStateService.saveState(runtimeState);
      newStateSaved = true;
      GameInstanceDto existingRunningState = stage.existingRunningState();
      if (existingRunningState != null) {
        sessionStateService.deleteState(existingRunningState.tenantId(), existingRunningState.id());
        WorldInstanceLifecycleSnapshot existingLifecycle =
            readWorldInstanceLifecycle(existingRunningState);
        if (existingLifecycle.getStatus()
            == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING) {
          oldWorldTerminationRequested = true;
          throw new LifecycleOutcomeException(
              "WORLD_TERMINATION_IN_PROGRESS", "replaced session is already terminating");
        }
        if (existingLifecycle.getStatus()
            == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED) {
          oldWorldTerminationRequested = true;
          oldWorldTerminationCompleted = true;
        } else {
          requireLifecycleStatus(
              existingLifecycle,
              WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE);
          oldWorldTerminationRequested = true;
          terminateWorldInstance(
              existingRunningState,
              existingLifecycle.getLifecycleEpoch(),
              "session-replace-" + stage.startingState().id() + "-" + UUID.randomUUID(),
              "session replacement requested");
          oldWorldTerminationCompleted = true;
        }
        inTransaction(
            () -> {
              markSessionStopped(existingRunningState.id());
              return null;
            },
            "finalize replaced session stop");
        oldSessionStopped = true;
      }
      worldActivationMayHaveCommitted = true;
      activatePreparedWorldInstance(preparedWorldInstance);
      runtimeState = withStatus(stage.startingState(), STATUS_RUNNING);
      sessionStateService.saveState(runtimeState);
      finalized = inTransaction(() -> finalizeStartedSession(stage), "finalize session start");
    } catch (RuntimeException ex) {
      compensateStartFailure(
          stage,
          runtimeState,
          newStateSaved,
          oldWorldTerminationRequested,
          oldWorldTerminationCompleted,
          oldSessionStopped,
          worldActivationMayHaveCommitted,
          preparedWorldInstance);
      throw ex;
    }
    try {
      meterRegistry.counter("game_sessions_started_total").increment();
    } catch (RuntimeException metricFailure) {
      logger.warn("Failed to record started game session metric", metricFailure);
    }
    return finalized;
  }

  /**
   * Starts or resumes a local fixture launch under a durable, tenant/request-bound owner identity.
   * This method has no transport binding; the run-owned fixture coordinator is its only caller.
   */
  @Override
  @Timed(value = "gamesession.run_owned_initial_launch")
  public RunOwnedInitialLaunchResult startRunOwnedInitialLaunch(StartSessionRequest request) {
    validateRunOwnedLaunchRequest(request);
    ResolvedLaunchDescriptor resolvedLaunchDescriptor = preflightLaunch(request);
    requireDescriptorMatchesRequest(request, resolvedLaunchDescriptor);
    String requestDigest = runOwnedLaunchRequestDigest(request);
    GameInstance reserved =
        inTransaction(
            () -> reserveRunOwnedInitialLaunch(request, resolvedLaunchDescriptor, requestDigest),
            "reserve run-owned initial launch");
    long gameInstanceId = reserved.getId();
    requireRunOwnedTargetCanResume(reserved);

    if (STATUS_RUNNING.equals(reserved.getStatus())) {
      long activeEpoch = requireRunOwnedRecordedActiveEpoch(reserved);
      WorldInstanceLifecycleSnapshot current =
          readRunOwnedWorldLifecycle(reserved, request, resolvedLaunchDescriptor);
      if (current.getStatus()
          == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING) {
        throw new IllegalStateException(
            "RUN_OWNED_INITIAL_LAUNCH_STATE_MISMATCH: running owner row is not ACTIVE in World");
      }
      if (current.getStatus()
          != WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE) {
        throw runOwnedWorldStatusFailure(current.getStatus());
      }
      requireRunOwnedActiveEpoch(current, activeEpoch);
      sessionStateService.saveState(withStatus(snapshot(reserved), STATUS_RUNNING));
      GameInstance completed =
          inTransaction(
              () ->
                  completeRunOwnedInitialLaunch(
                      request,
                      requestDigest,
                      resolvedLaunchDescriptor,
                      gameInstanceId,
                      activeEpoch),
              "complete run-owned initial launch retry");
      return new RunOwnedInitialLaunchResult(snapshot(completed), activeEpoch);
    }

    validateStartDependencies();

    WorldInstanceLifecycleSnapshot prepared =
        prepareRunOwnedWorldInstance(reserved, resolvedLaunchDescriptor, request);
    if (prepared.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING) {
      reserved =
          inTransaction(
              () ->
                  recordRunOwnedPreparingEpoch(
                      request,
                      requestDigest,
                      resolvedLaunchDescriptor,
                      gameInstanceId,
                      prepared.getLifecycleEpoch()),
              "record run-owned preparing epoch");
    } else {
      reserved =
          reloadRunOwnedInitialLaunch(
              request, requestDigest, resolvedLaunchDescriptor, gameInstanceId);
    }

    WorldInstanceLifecycleSnapshot current =
        readRunOwnedWorldLifecycle(reserved, request, resolvedLaunchDescriptor);
    long activeEpoch;
    if (current.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING) {
      if (STATUS_RUNNING.equals(reserved.getStatus())) {
        throw new IllegalStateException(
            "RUN_OWNED_INITIAL_LAUNCH_STATE_MISMATCH: running owner row is not ACTIVE in World");
      }
      if (reserved.getRunOwnedStartActiveEpoch() != null) {
        throw new IllegalStateException(
            "RUN_OWNED_INITIAL_LAUNCH_STATE_MISMATCH: starting owner row already has an active epoch");
      }
      long preparingEpoch = requireRunOwnedPreparingEpoch(reserved);
      activeEpoch = Math.addExact(preparingEpoch, 1L);
      if (current.getLifecycleEpoch() != preparingEpoch) {
        throw new IllegalStateException(
            "WORLD_LIFECYCLE_EPOCH_MISMATCH: PREPARING epoch differs from the durable launch fence");
      }
      if (STATUS_STARTING.equals(reserved.getStatus())) {
        sessionStateService.saveState(withStatus(snapshot(reserved), STATUS_STARTING));
      }
      activateAndReadBackRunOwnedWorld(
          reserved, request, resolvedLaunchDescriptor, preparingEpoch, activeEpoch);
    } else if (current.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE) {
      long preparingEpoch = requireRunOwnedPreparingEpoch(reserved);
      activeEpoch = Math.addExact(preparingEpoch, 1L);
      if (reserved.getRunOwnedStartActiveEpoch() != null
          && reserved.getRunOwnedStartActiveEpoch() != activeEpoch) {
        throw new IllegalStateException(
            "WORLD_LIFECYCLE_EPOCH_MISMATCH: ACTIVE epoch differs from the durable activation result");
      }
      requireRunOwnedActiveEpoch(current, activeEpoch);
    } else {
      throw runOwnedWorldStatusFailure(current.getStatus());
    }

    GameInstanceDto runningState = withStatus(snapshot(reserved), STATUS_RUNNING);
    sessionStateService.saveState(runningState);
    GameInstance completed =
        inTransaction(
            () ->
                completeRunOwnedInitialLaunch(
                    request, requestDigest, resolvedLaunchDescriptor, gameInstanceId, activeEpoch),
            "complete run-owned initial launch");
    return new RunOwnedInitialLaunchResult(snapshot(completed), activeEpoch);
  }

  @Override
  @Timed(value = "gamesession.stop")
  public GameInstanceDto stopSession(long sessionId) {
    GameInstanceDto runningState = inTransaction(() -> stageStopSession(sessionId), "stage stop");
    boolean worldTerminationRequested = false;
    boolean worldTerminationCompleted = false;
    try {
      sessionStateService.deleteState(runningState.tenantId(), runningState.id());
      WorldInstanceLifecycleSnapshot lifecycle = readWorldInstanceLifecycle(runningState);
      if (lifecycle.getStatus()
          == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED) {
        worldTerminationRequested = true;
        worldTerminationCompleted = true;
      } else if (lifecycle.getStatus()
          == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING) {
        worldTerminationRequested = true;
        throw new LifecycleOutcomeException(
            "WORLD_TERMINATION_IN_PROGRESS", "session termination is already in progress");
      } else {
        requireLifecycleStatus(
            lifecycle, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE);
        worldTerminationRequested = true;
        terminateWorldInstance(
            runningState,
            lifecycle.getLifecycleEpoch(),
            "session-stop-" + UUID.randomUUID(),
            "session stop requested");
        worldTerminationCompleted = true;
      }
      return inTransaction(() -> finalizeStoppedSession(sessionId), "finalize stop");
    } catch (RuntimeException ex) {
      compensateStopFailure(runningState, worldTerminationRequested, worldTerminationCompleted);
      throw ex;
    }
  }

  @Override
  @Timed(value = "gamesession.restart")
  public GameInstanceDto restartSession(long sessionId) {
    GameInstanceDto previousState =
        inTransaction(() -> stageRestartSession(sessionId), "stage restart");
    GameInstanceDto runtimeState = withStatus(previousState, STATUS_RUNNING);
    boolean stateSaved = false;
    try {
      sessionStateService.saveState(runtimeState);
      stateSaved = true;
      return inTransaction(() -> finalizeRestartedSession(sessionId), "finalize restart");
    } catch (RuntimeException ex) {
      compensateRestartFailure(previousState, stateSaved);
      throw ex;
    }
  }

  private StartSessionStage stageStartSession(
      StartSessionRequest request,
      ResolvedLaunchDescriptor resolvedLaunchDescriptor,
      boolean replaceExistingFirst) {
    GameInstanceDto existingRunningState = null;
    if (replaceExistingFirst) {
      existingRunningState =
          repository
              .findFirstByTenantIdAndOwnerAccountIdAndStatus(
                  request.tenantId(), request.ownerAccountId(), STATUS_RUNNING)
              .map(this::snapshot)
              .orElse(null);
      if (existingRunningState != null) {
        GameInstance existingRunning =
            repository
                .findById(existingRunningState.id())
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        existingRunning.setStatus(STATUS_STOPPING);
        repository.save(existingRunning);
      }
    }
    GameInstance instance = new GameInstance();
    instance.setTenantId(request.tenantId());
    instance.setRuntimeVersion(Long.toString(resolvedLaunchDescriptor.versionId()));
    // The launch descriptor carries only a candidate patch.  The authoritative pin tuple is
    // allocated by the owner pin transition, so a new instance must remain semantically
    // UNPINNED until that transition commits all owner fields together.
    instance.setGameTemplateId(request.gameTemplateId());
    instance.setLaunchDescriptorId(resolvedLaunchDescriptor.launchDescriptorId());
    instance.setVersionId(resolvedLaunchDescriptor.versionId());
    instance.setReleaseBundleId(resolvedLaunchDescriptor.releaseBundleId());
    instance.setVersionStateEpoch(resolvedLaunchDescriptor.versionStateEpoch());
    instance.setGenerationConfigRevision(resolvedLaunchDescriptor.generationConfigRevision());
    instance.setRemapSetId(resolvedLaunchDescriptor.remapSetId());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus(STATUS_STARTING);
    return new StartSessionStage(snapshot(repository.save(instance)), existingRunningState);
  }

  private GameInstanceDto finalizeStartedSession(StartSessionStage stage) {
    GameInstance instance =
        repository
            .findById(stage.startingState().id())
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus(STATUS_RUNNING);
    return mapper.toDto(repository.save(instance));
  }

  private void markSessionStopped(long sessionId) {
    GameInstance existingRunning =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    existingRunning.setStatus(STATUS_STOPPED);
    repository.save(existingRunning);
  }

  private GameInstanceDto stageStopSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    GameInstanceDto runningState = snapshot(instance);
    instance.setStatus(STATUS_STOPPING);
    repository.save(instance);
    return runningState;
  }

  private GameInstanceDto finalizeStoppedSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus(STATUS_STOPPED);
    return mapper.toDto(repository.save(instance));
  }

  private GameInstanceDto stageRestartSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    GameInstanceDto previousState = snapshot(instance);
    instance.setStatus(STATUS_STARTING);
    repository.save(instance);
    return previousState;
  }

  private GameInstanceDto finalizeRestartedSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus(STATUS_RUNNING);
    return mapper.toDto(repository.save(instance));
  }

  private void validateStartDependencies() {
    if (gameLogicClient == null || entityManagementClient == null || sagaRunner == null) {
      return;
    }
    var saga =
        new SagaBuilder("startSession")
            .step("checkEntityService", () -> entityManagementClient.ping())
            .step("notifyGameLogic", () -> gameLogicClient.ping())
            .build();
    try {
      sagaRunner.run(saga);
    } catch (SagaException e) {
      logger.error("Saga failed during session start", e);
      throw new IllegalStateException("Failed to start session", e);
    }
  }

  private void compensateStartFailure(
      StartSessionStage stage,
      GameInstanceDto runtimeState,
      boolean newStateSaved,
      boolean oldWorldTerminationRequested,
      boolean oldWorldTerminationCompleted,
      boolean oldSessionStopped,
      boolean worldActivationMayHaveCommitted,
      @Nullable PreparedWorldInstance preparedWorldInstance) {
    if (newStateSaved && !worldActivationMayHaveCommitted) {
      runRollbackSafely(
          "delete failed started session state",
          () -> sessionStateService.deleteState(runtimeState.tenantId(), runtimeState.id()));
    }
    GameInstanceDto existingRunningState = stage.existingRunningState();
    if (existingRunningState != null && !oldWorldTerminationRequested) {
      runRollbackSafely(
          "restore replaced session runtime state",
          () -> sessionStateService.saveState(existingRunningState));
      runRollbackSafely(
          "restore replaced session row",
          () ->
              inTransaction(
                  () -> {
                    GameInstance existingRunning =
                        repository
                            .findById(existingRunningState.id())
                            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
                    restoreSessionSnapshot(existingRunning, existingRunningState);
                    return null;
                  },
                  "restore replaced session row"));
    }
    if (!worldActivationMayHaveCommitted
        && preparedWorldInstance != null
        && worldManagementClient != null) {
      runRollbackSafely(
          "fail prepared world instance",
          () ->
              failPreparedWorldInstance(
                  preparedWorldInstance, "session start failed before admission opened"));
    }
    if (worldActivationMayHaveCommitted) {
      logger.warn(
          "Quarantining session {} in STARTING after World activation may have committed",
          stage.startingState().id());
      if (STATUS_RUNNING.equals(runtimeState.status())) {
        runRollbackSafely(
            "restore quarantined starting session runtime state",
            () ->
                sessionStateService.saveState(withStatus(stage.startingState(), STATUS_STARTING)));
      }
      runRollbackSafely(
          "retain quarantined starting session row",
          () ->
              inTransaction(
                  () -> {
                    GameInstance starting =
                        repository
                            .findById(stage.startingState().id())
                            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
                    starting.setStatus(STATUS_STARTING);
                    repository.save(starting);
                    return null;
                  },
                  "retain quarantined starting session row"));
    } else {
      runRollbackSafely(
          "delete failed starting session row",
          () ->
              inTransaction(
                  () -> {
                    repository.deleteById(stage.startingState().id());
                    return null;
                  },
                  "delete failed starting session row"));
    }
    if (oldWorldTerminationCompleted && !oldSessionStopped && existingRunningState != null) {
      runRollbackSafely(
          "finalize terminated replaced session row",
          () ->
              inTransaction(
                  () -> {
                    markSessionStopped(existingRunningState.id());
                    return null;
                  },
                  "finalize terminated replaced session row"));
    } else if (oldWorldTerminationRequested
        && !oldWorldTerminationCompleted
        && !oldSessionStopped
        && existingRunningState != null) {
      logger.warn(
          "Leaving replaced session {} STOPPING after ambiguous World termination",
          existingRunningState.id());
    }
  }

  private void compensateStopFailure(
      GameInstanceDto runningState,
      boolean worldTerminationRequested,
      boolean worldTerminationCompleted) {
    if (worldTerminationCompleted) {
      runRollbackSafely(
          "finalize terminated session row",
          () ->
              inTransaction(
                  () -> {
                    finalizeStoppedSession(runningState.id());
                    return null;
                  },
                  "finalize terminated session row"));
    } else if (!worldTerminationRequested) {
      runRollbackSafely(
          "restore stopped session runtime state",
          () -> sessionStateService.saveState(runningState));
      runRollbackSafely(
          "restore stopping session row",
          () ->
              inTransaction(
                  () -> {
                    GameInstance instance =
                        repository
                            .findById(runningState.id())
                            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
                    restoreSessionSnapshot(instance, runningState);
                    return null;
                  },
                  "restore stopping session row"));
    } else {
      logger.warn(
          "Leaving session {} STOPPING with runtime state absent after ambiguous World termination",
          runningState.id());
    }
  }

  private void compensateRestartFailure(GameInstanceDto previousState, boolean stateSaved) {
    if (stateSaved) {
      runRollbackSafely(
          "delete restarted session state",
          () -> sessionStateService.deleteState(previousState.tenantId(), previousState.id()));
    }
    runRollbackSafely(
        "restore restarted session row",
        () ->
            inTransaction(
                () -> {
                  GameInstance instance =
                      repository
                          .findById(previousState.id())
                          .orElseThrow(() -> new IllegalArgumentException("Session not found"));
                  restoreSessionSnapshot(instance, previousState);
                  return null;
                },
                "restore restarted session row"));
  }

  private GameInstanceDto snapshot(GameInstance instance) {
    return new GameInstanceDto(
        instance.getId(),
        instance.getTenantId(),
        instance.getRuntimeVersion(),
        instance.getScriptPatchVersion(),
        instance.getScriptPatchBaseVersionId(),
        instance.getScriptPinEpoch(),
        instance.getScriptPatchPinnedControlPlaneRequestId(),
        instance.getGameTemplateId(),
        instance.getLaunchDescriptorId(),
        instance.getVersionId(),
        instance.getReleaseBundleId(),
        instance.getVersionStateEpoch(),
        instance.getGenerationConfigRevision(),
        instance.getRemapSetId(),
        instance.getOwnerAccountId(),
        instance.getStatus());
  }

  private void restoreSessionSnapshot(GameInstance instance, GameInstanceDto snapshot) {
    instance.setStatus(snapshot.status());
    instance.setRuntimeVersion(snapshot.runtimeVersion());
    instance.setScriptPatchVersion(snapshot.scriptPatchVersion());
    instance.setScriptPatchBaseVersionId(snapshot.scriptPatchBaseVersionId());
    instance.setScriptPinEpoch(snapshot.scriptPinEpoch());
    instance.setScriptPatchPinnedControlPlaneRequestId(snapshot.scriptPinControlPlaneRequestId());
    instance.setGameTemplateId(snapshot.gameTemplateId());
    instance.setLaunchDescriptorId(snapshot.launchDescriptorId());
    instance.setVersionId(snapshot.versionId());
    instance.setReleaseBundleId(snapshot.releaseBundleId());
    instance.setVersionStateEpoch(snapshot.versionStateEpoch());
    instance.setGenerationConfigRevision(snapshot.generationConfigRevision());
    instance.setRemapSetId(snapshot.remapSetId());
    instance.setOwnerAccountId(snapshot.ownerAccountId());
    instance.setTenantId(snapshot.tenantId());
    repository.save(instance);
  }

  private void validateRunOwnedLaunchRequest(StartSessionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("run-owned initial launch request is required");
    }
    RequestIdValidation.requirePositiveLong(request.tenantId(), "tenantId");
    RequestIdValidation.requirePositiveLong(request.gameTemplateId(), "gameTemplateId");
    RequestIdValidation.requirePositiveLong(request.ownerAccountId(), "ownerAccountId");
    if (request.controlPlaneRequestId() == null
        || request.controlPlaneRequestId().isBlank()
        || request.controlPlaneRequestId().length() > 128) {
      throw new IllegalArgumentException(
          "controlPlaneRequestId must contain 1 to 128 nonblank characters");
    }
  }

  private static String runOwnedLaunchRequestDigest(StartSessionRequest request) {
    StringBuilder preimage = new StringBuilder();
    appendDigestField(preimage, "schema", RUN_OWNED_LAUNCH_DIGEST_SCHEMA);
    appendDigestField(preimage, "tenantId", Long.toString(request.tenantId()));
    appendDigestField(preimage, "gameTemplateId", Long.toString(request.gameTemplateId()));
    appendDigestField(preimage, "controlPlaneRequestId", request.controlPlaneRequestId());
    appendDigestField(preimage, "ownerAccountId", Long.toString(request.ownerAccountId()));
    appendDigestField(preimage, "replaceExistingFirst", "false");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(preimage.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private static void appendDigestField(StringBuilder preimage, String name, String value) {
    int nameByteLength = name.getBytes(StandardCharsets.UTF_8).length;
    int valueByteLength = value.getBytes(StandardCharsets.UTF_8).length;
    preimage
        .append(nameByteLength)
        .append(':')
        .append(name)
        .append(valueByteLength)
        .append(':')
        .append(value);
  }

  private void requireDescriptorMatchesRequest(
      StartSessionRequest request, ResolvedLaunchDescriptor descriptor) {
    if (descriptor.tenantId() != request.tenantId()
        || descriptor.gameTemplateId() != request.gameTemplateId()
        || !request.controlPlaneRequestId().equals(descriptor.controlPlaneRequestId())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_REQUEST_MISMATCH: resolved descriptor is not bound to this request");
    }
    if (descriptor.launchDescriptorId() == null
        || descriptor.launchDescriptorId().isBlank()
        || descriptor.versionId() <= 0L
        || descriptor.releaseBundleId() <= 0L
        || descriptor.versionStateEpoch() <= 0L
        || descriptor.generationConfigRevision() == null
        || descriptor.generationConfigRevision().isBlank()
        || descriptor.publishedReleaseBundleRef() == null
        || descriptor.publishedReleaseBundleRef().isBlank()
        || !releaseBundleRef(
                request.tenantId(), descriptor.versionId(), descriptor.releaseBundleId())
            .equals(descriptor.publishedReleaseBundleRef())) {
      throw new IllegalArgumentException(
          "LAUNCH_DESCRIPTOR_INCOMPLETE: resolved descriptor is missing exact published inputs");
    }
  }

  private GameInstance reserveRunOwnedInitialLaunch(
      StartSessionRequest request, ResolvedLaunchDescriptor descriptor, String requestDigest) {
    repository.lockRunOwnedStartIdentity(request.tenantId(), request.controlPlaneRequestId());
    Optional<GameInstance> existing =
        repository.findByTenantIdAndRunOwnedStartRequestIdForUpdate(
            request.tenantId(), request.controlPlaneRequestId());
    if (existing.isPresent()) {
      requireSameRunOwnedInitialLaunch(existing.get(), request, descriptor, requestDigest);
      return existing.get();
    }

    GameInstance instance = new GameInstance();
    instance.setTenantId(request.tenantId());
    instance.setRuntimeVersion(Long.toString(descriptor.versionId()));
    instance.setGameTemplateId(request.gameTemplateId());
    instance.setLaunchDescriptorId(descriptor.launchDescriptorId());
    instance.setVersionId(descriptor.versionId());
    instance.setReleaseBundleId(descriptor.releaseBundleId());
    instance.setVersionStateEpoch(descriptor.versionStateEpoch());
    instance.setGenerationConfigRevision(descriptor.generationConfigRevision());
    instance.setRemapSetId(descriptor.remapSetId());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus(STATUS_STARTING);
    instance.setRunOwnedStartRequestId(request.controlPlaneRequestId());
    instance.setRunOwnedStartRequestDigest(requestDigest);
    instance.setRunOwnedStartPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef());
    return repository.save(instance);
  }

  private void requireSameRunOwnedInitialLaunch(
      GameInstance instance,
      StartSessionRequest request,
      ResolvedLaunchDescriptor descriptor,
      String requestDigest) {
    if (!request.controlPlaneRequestId().equals(instance.getRunOwnedStartRequestId())
        || !requestDigest.equals(instance.getRunOwnedStartRequestDigest())
        || !Objects.equals(instance.getTenantId(), request.tenantId())
        || !Objects.equals(instance.getOwnerAccountId(), request.ownerAccountId())
        || !Objects.equals(instance.getGameTemplateId(), request.gameTemplateId())
        || !Objects.equals(instance.getRuntimeVersion(), Long.toString(descriptor.versionId()))
        || !Objects.equals(instance.getLaunchDescriptorId(), descriptor.launchDescriptorId())
        || !Objects.equals(instance.getVersionId(), descriptor.versionId())
        || !Objects.equals(instance.getReleaseBundleId(), descriptor.releaseBundleId())
        || !Objects.equals(instance.getVersionStateEpoch(), descriptor.versionStateEpoch())
        || !Objects.equals(
            instance.getGenerationConfigRevision(), descriptor.generationConfigRevision())
        || !Objects.equals(instance.getRemapSetId(), descriptor.remapSetId())
        || !Objects.equals(
            instance.getRunOwnedStartPublishedReleaseBundleRef(),
            descriptor.publishedReleaseBundleRef())) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_IDENTITY_CONFLICT: request or resolved descriptor changed");
    }
  }

  private void requireRunOwnedTargetCanResume(GameInstance instance) {
    if (instance.getId() == null
        || instance.getRunOwnedStartRequestId() == null
        || instance.getRunOwnedStartRequestDigest() == null) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_IDENTITY_UNAVAILABLE: durable request identity is incomplete");
    }
    if (!STATUS_STARTING.equals(instance.getStatus())
        && !STATUS_RUNNING.equals(instance.getStatus())) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_TARGET_NOT_RESUMABLE: existing instance is not starting or running");
    }
    if (STATUS_RUNNING.equals(instance.getStatus())) {
      requireRunOwnedRecordedActiveEpoch(instance);
    }
  }

  private long requireRunOwnedRecordedActiveEpoch(GameInstance instance) {
    long preparingEpoch = requireRunOwnedPreparingEpoch(instance);
    Long activeEpoch = instance.getRunOwnedStartActiveEpoch();
    if (activeEpoch == null || activeEpoch <= 0L) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_EPOCH_UNAVAILABLE: running instance has no active epoch proof");
    }
    if (activeEpoch != Math.addExact(preparingEpoch, 1L)) {
      throw new IllegalStateException(
          "WORLD_LIFECYCLE_EPOCH_MISMATCH: durable ACTIVE epoch differs from the prepare fence");
    }
    return activeEpoch;
  }

  private GameInstance recordRunOwnedPreparingEpoch(
      StartSessionRequest request,
      String requestDigest,
      ResolvedLaunchDescriptor descriptor,
      long gameInstanceId,
      long preparingEpoch) {
    if (preparingEpoch <= 0L) {
      throw new IllegalStateException(
          "WORLD_AUTHORITY_MALFORMED: lifecycle epoch must be positive");
    }
    GameInstance instance =
        repository
            .findByTenantIdAndGameInstanceIdForUpdate(request.tenantId(), gameInstanceId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "RUN_OWNED_INITIAL_LAUNCH_IDENTITY_UNAVAILABLE: instance row is missing"));
    requireSameRunOwnedInitialLaunch(instance, request, descriptor, requestDigest);
    Long recordedEpoch = instance.getRunOwnedStartPreparingEpoch();
    if (recordedEpoch != null) {
      if (recordedEpoch != preparingEpoch) {
        throw new IllegalStateException(
            "WORLD_LIFECYCLE_EPOCH_MISMATCH: PREPARING epoch changed across retries");
      }
      return instance;
    }
    if (instance.getRunOwnedStartActiveEpoch() != null) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_EPOCH_MISMATCH: active epoch exists without its prepare fence");
    }
    instance.setRunOwnedStartPreparingEpoch(preparingEpoch);
    return repository.save(instance);
  }

  private GameInstance reloadRunOwnedInitialLaunch(
      StartSessionRequest request,
      String requestDigest,
      ResolvedLaunchDescriptor descriptor,
      long gameInstanceId) {
    return inTransaction(
        () -> {
          GameInstance instance =
              repository
                  .findByTenantIdAndGameInstanceIdForUpdate(request.tenantId(), gameInstanceId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "RUN_OWNED_INITIAL_LAUNCH_IDENTITY_UNAVAILABLE: instance row is missing"));
          requireSameRunOwnedInitialLaunch(instance, request, descriptor, requestDigest);
          return instance;
        },
        "reload run-owned initial launch");
  }

  private GameInstance completeRunOwnedInitialLaunch(
      StartSessionRequest request,
      String requestDigest,
      ResolvedLaunchDescriptor descriptor,
      long gameInstanceId,
      long activeEpoch) {
    GameInstance instance =
        repository
            .findByTenantIdAndGameInstanceIdForUpdate(request.tenantId(), gameInstanceId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "RUN_OWNED_INITIAL_LAUNCH_IDENTITY_UNAVAILABLE: instance row is missing"));
    requireSameRunOwnedInitialLaunch(instance, request, descriptor, requestDigest);
    long expectedActiveEpoch = Math.addExact(requireRunOwnedPreparingEpoch(instance), 1L);
    if (activeEpoch != expectedActiveEpoch) {
      throw new IllegalStateException(
          "WORLD_LIFECYCLE_EPOCH_MISMATCH: ACTIVE epoch differs from the durable activation fence");
    }
    if (instance.getRunOwnedStartActiveEpoch() != null
        && instance.getRunOwnedStartActiveEpoch() != activeEpoch) {
      throw new IllegalStateException(
          "WORLD_LIFECYCLE_EPOCH_MISMATCH: ACTIVE epoch changed across retries");
    }
    if (!STATUS_STARTING.equals(instance.getStatus())
        && !STATUS_RUNNING.equals(instance.getStatus())) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_TARGET_NOT_RESUMABLE: existing instance is not starting or running");
    }
    instance.setRunOwnedStartActiveEpoch(activeEpoch);
    instance.setStatus(STATUS_RUNNING);
    return repository.save(instance);
  }

  private long requireRunOwnedPreparingEpoch(GameInstance instance) {
    Long epoch = instance.getRunOwnedStartPreparingEpoch();
    if (epoch == null || epoch <= 0L) {
      throw new IllegalStateException(
          "RUN_OWNED_INITIAL_LAUNCH_EPOCH_UNAVAILABLE: exact PREPARING epoch is not durable");
    }
    return epoch;
  }

  private WorldInstanceLifecycleSnapshot prepareRunOwnedWorldInstance(
      GameInstance instance, ResolvedLaunchDescriptor descriptor, StartSessionRequest request) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_PREPARATION_AUTHORITY_UNAVAILABLE);
    }
    final PrepareWorldInstanceResponse response;
    try {
      response =
          worldManagementClient.prepareWorldInstance(
              request.tenantId(),
              instance.getId(),
              request.gameTemplateId(),
              request.controlPlaneRequestId(),
              descriptor.launchDescriptorId(),
              descriptor.versionId(),
              instance.getScriptPatchVersion(),
              descriptor.runtimeFlagsJson(),
              descriptor.generationConfigRevision(),
              descriptor.releaseBundleId(),
              descriptor.publishedReleaseBundleRef(),
              descriptor.versionStateEpoch(),
              descriptor.remapSetId());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(WORLD_PREPARATION_AUTHORITY_UNAVAILABLE, exception);
    }
    if (response == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (response.hasError()) {
      throw new IllegalStateException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    WorldInstanceLifecycleSnapshot snapshot =
        requireWorldSnapshot(
            response.hasWorldInstance(),
            response.getWorldInstance(),
            request.tenantId(),
            instance.getId(),
            null);
    requireRunOwnedWorldDescriptor(snapshot, request, descriptor, instance.getId());
    requireKnownRunOwnedWorldStatus(snapshot.getStatus());
    return snapshot;
  }

  private WorldInstanceLifecycleSnapshot readRunOwnedWorldLifecycle(
      GameInstance instance, StartSessionRequest request, ResolvedLaunchDescriptor descriptor) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_LIFECYCLE_AUTHORITY_UNAVAILABLE);
    }
    final GetWorldInstanceLifecycleResponse response;
    try {
      response =
          worldManagementClient.getWorldInstanceLifecycle(request.tenantId(), instance.getId());
    } catch (RuntimeException exception) {
      throw new IllegalStateException(WORLD_LIFECYCLE_AUTHORITY_UNAVAILABLE, exception);
    }
    if (response == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (response.hasError()) {
      throw new IllegalStateException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    WorldInstanceLifecycleSnapshot snapshot =
        requireWorldSnapshot(
            response.hasWorldInstance(),
            response.getWorldInstance(),
            request.tenantId(),
            instance.getId(),
            null);
    requireRunOwnedWorldDescriptor(snapshot, request, descriptor, instance.getId());
    requireKnownRunOwnedWorldStatus(snapshot.getStatus());
    return snapshot;
  }

  private WorldInstanceLifecycleSnapshot activateAndReadBackRunOwnedWorld(
      GameInstance instance,
      StartSessionRequest request,
      ResolvedLaunchDescriptor descriptor,
      long preparingEpoch,
      long activeEpoch) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_ACTIVATION_AUTHORITY_UNAVAILABLE);
    }
    final ActivatePreparedWorldInstanceResponse response;
    try {
      response =
          worldManagementClient.activatePreparedWorldInstance(
              request.tenantId(), instance.getId(), preparingEpoch);
    } catch (RuntimeException activationFailure) {
      return reconcileRunOwnedActivationFailure(
          instance, request, descriptor, preparingEpoch, activeEpoch, activationFailure);
    }
    if (response == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (response.hasError()) {
      return reconcileRunOwnedActivationFailure(
          instance,
          request,
          descriptor,
          preparingEpoch,
          activeEpoch,
          new IllegalStateException(
              response.getError().getCode() + ": " + response.getError().getMessage()));
    }
    WorldInstanceLifecycleSnapshot activated =
        requireWorldSnapshot(
            response.hasWorldInstance(),
            response.getWorldInstance(),
            request.tenantId(),
            instance.getId(),
            WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE);
    requireRunOwnedWorldDescriptor(activated, request, descriptor, instance.getId());
    requireRunOwnedActiveEpoch(activated, activeEpoch);

    WorldInstanceLifecycleSnapshot readback =
        readRunOwnedWorldLifecycle(instance, request, descriptor);
    if (readback.getStatus()
        != WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE) {
      throw runOwnedWorldStatusFailure(readback.getStatus());
    }
    requireRunOwnedActiveEpoch(readback, activeEpoch);
    return readback;
  }

  private WorldInstanceLifecycleSnapshot reconcileRunOwnedActivationFailure(
      GameInstance instance,
      StartSessionRequest request,
      ResolvedLaunchDescriptor descriptor,
      long preparingEpoch,
      long activeEpoch,
      RuntimeException activationFailure) {
    WorldInstanceLifecycleSnapshot current =
        readRunOwnedWorldLifecycle(instance, request, descriptor);
    if (current.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE) {
      requireRunOwnedActiveEpoch(current, activeEpoch);
      return current;
    }
    if (current.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING) {
      if (current.getLifecycleEpoch() != preparingEpoch) {
        throw new IllegalStateException(
            "WORLD_LIFECYCLE_EPOCH_MISMATCH: PREPARING epoch differs from the durable launch fence",
            activationFailure);
      }
      throw new IllegalStateException(WORLD_ACTIVATION_AUTHORITY_UNAVAILABLE, activationFailure);
    }
    throw runOwnedWorldStatusFailure(current.getStatus());
  }

  private void requireRunOwnedWorldDescriptor(
      WorldInstanceLifecycleSnapshot snapshot,
      StartSessionRequest request,
      ResolvedLaunchDescriptor descriptor,
      long gameInstanceId) {
    final long tenantId;
    final long instanceId;
    final long templateId;
    final long versionId;
    final long releaseBundleId;
    try {
      tenantId = RequestIdValidation.requirePositiveLong(snapshot.getTenantId(), "tenantId");
      instanceId =
          RequestIdValidation.requirePositiveLong(snapshot.getGameInstanceId(), "gameInstanceId");
      templateId =
          RequestIdValidation.requirePositiveLong(snapshot.getGameTemplateId(), "gameTemplateId");
      versionId = RequestIdValidation.requirePositiveLong(snapshot.getVersionId(), "versionId");
      releaseBundleId =
          RequestIdValidation.requirePositiveLong(snapshot.getReleaseBundleId(), "releaseBundleId");
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "WORLD_AUTHORITY_MALFORMED: " + exception.getMessage(), exception);
    }
    if (tenantId != request.tenantId()
        || instanceId != gameInstanceId
        || templateId != request.gameTemplateId()
        || !request.controlPlaneRequestId().equals(snapshot.getControlPlaneRequestId())
        || !descriptor.launchDescriptorId().equals(snapshot.getLaunchDescriptorId())
        || versionId != descriptor.versionId()
        || releaseBundleId != descriptor.releaseBundleId()
        || !descriptor.generationConfigRevision().equals(snapshot.getGenerationConfigRevision())
        || !descriptor.publishedReleaseBundleRef().equals(snapshot.getPublishedReleaseBundleRef())
        || snapshot.getVersionStateEpoch() != descriptor.versionStateEpoch()
        || !Objects.equals(
            normalizeBlank(descriptor.remapSetId()), normalizeBlank(snapshot.getRemapSetId()))) {
      throw new IllegalStateException(
          "WORLD_AUTHORITY_DESCRIPTOR_MISMATCH: lifecycle readback differs from the resolved launch descriptor");
    }
  }

  private void requireKnownRunOwnedWorldStatus(WorldInstanceLifecycleStatus status) {
    if (status == null
        || status == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_UNSPECIFIED
        || status == WorldInstanceLifecycleStatus.UNRECOGNIZED) {
      throw new IllegalStateException("WORLD_AUTHORITY_MALFORMED: lifecycle status is unavailable");
    }
  }

  private void requireRunOwnedActiveEpoch(
      WorldInstanceLifecycleSnapshot snapshot, long expectedActiveEpoch) {
    if (snapshot.getLifecycleEpoch() != expectedActiveEpoch) {
      throw new IllegalStateException(
          "WORLD_LIFECYCLE_EPOCH_MISMATCH: ACTIVE epoch differs from the durable activation result");
    }
  }

  private RuntimeException runOwnedWorldStatusFailure(WorldInstanceLifecycleStatus status) {
    if (status
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION) {
      return new LifecycleOutcomeException(
          "WORLD_INSTANCE_LIFECYCLE_FAILED_PRE_ACTIVATION",
          "run-owned instance failed before activation and remains terminal for this request");
    }
    if (status == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING) {
      return new LifecycleOutcomeException(
          "WORLD_TERMINATION_IN_PROGRESS", "run-owned instance termination is in progress");
    }
    if (status == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED) {
      return new LifecycleOutcomeException(
          "WORLD_INSTANCE_TERMINATED", "run-owned instance is already terminated");
    }
    return new IllegalStateException(
        "WORLD_AUTHORITY_MALFORMED: lifecycle status cannot resume initial launch");
  }

  private String normalizeBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private GameInstanceDto withStatus(GameInstanceDto snapshot, String status) {
    return new GameInstanceDto(
        snapshot.id(),
        snapshot.tenantId(),
        snapshot.runtimeVersion(),
        snapshot.scriptPatchVersion(),
        snapshot.scriptPatchBaseVersionId(),
        snapshot.scriptPinEpoch(),
        snapshot.scriptPinControlPlaneRequestId(),
        snapshot.gameTemplateId(),
        snapshot.launchDescriptorId(),
        snapshot.versionId(),
        snapshot.releaseBundleId(),
        snapshot.versionStateEpoch(),
        snapshot.generationConfigRevision(),
        snapshot.remapSetId(),
        snapshot.ownerAccountId(),
        status);
  }

  private ResolvedLaunchDescriptor preflightLaunch(StartSessionRequest request) {
    if (gameDesignClient == null) {
      throw new IllegalStateException("launch descriptor authority unavailable");
    }
    var descriptorResponse =
        gameDesignClient.resolveLaunchDescriptor(
            request.tenantId(), request.gameTemplateId(), request.controlPlaneRequestId());
    if (descriptorResponse.hasError()) {
      throw new IllegalArgumentException(
          descriptorResponse.getError().getCode()
              + ": "
              + descriptorResponse.getError().getMessage());
    }
    var descriptor = descriptorResponse.getLaunchDescriptor();
    var bundleResponse =
        gameDesignClient.getPublishedReleaseBundle(request.tenantId(), descriptor.getVersionId());
    if (bundleResponse.hasError()) {
      throw new IllegalArgumentException(
          bundleResponse.getError().getCode() + ": " + bundleResponse.getError().getMessage());
    }
    var bundle = bundleResponse.getBundle();
    requireSupportedReleaseAttestationSchema(bundle.getAttestationSchemaVersion());
    if (bundle.getId() != descriptor.getReleaseBundleId()
        || bundle.getVersionId() != descriptor.getVersionId()
        || !bundle.getGenerationConfigRevision().equals(descriptor.getGenerationConfigRevision())
        || !releaseBundleRef(request.tenantId(), descriptor.getVersionId(), bundle.getId())
            .equals(descriptor.getPublishedReleaseBundleRef())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: resolved launch descriptor does not match the published"
              + " release bundle");
    }
    validatePublishedAssetProof(request.tenantId(), descriptor.getVersionId(), bundle);
    var versionStateResponse =
        gameDesignClient.getVersionState(request.tenantId(), descriptor.getVersionId());
    if (versionStateResponse.hasError()) {
      throw new IllegalArgumentException(
          versionStateResponse.getError().getCode()
              + ": "
              + versionStateResponse.getError().getMessage());
    }
    var versionState = versionStateResponse.getVersionState();
    if (versionState.getVersionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
        && versionState.getVersionState() != VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE) {
      throw new IllegalArgumentException(
          "VERSION_STATE_EPOCH_STALE: resolved version is not activation-eligible");
    }
    if (versionState.getVersionStateEpoch() != descriptor.getVersionStateEpoch()) {
      throw new IllegalArgumentException(
          "VERSION_STATE_EPOCH_STALE: resolved launch descriptor epoch does not match current"
              + " version state");
    }
    return new ResolvedLaunchDescriptor(
        descriptor.getLaunchDescriptorId(),
        requirePositiveExternalId(descriptor.getCanonicalTenantId(), "tenantId"),
        descriptor.getGameTemplateId(),
        descriptor.getControlPlaneRequestId(),
        descriptor.getVersionId(),
        descriptor.getScriptPatchVersion().isBlank() ? null : descriptor.getScriptPatchVersion(),
        descriptor.getRuntimeFlagsJson(),
        descriptor.getGenerationConfigRevision(),
        descriptor.getVersionStateEpoch(),
        descriptor.getReleaseBundleId(),
        descriptor.getPublishedReleaseBundleRef(),
        descriptor.getRemapSetId().isBlank() ? null : descriptor.getRemapSetId());
  }

  private void validatePublishedAssetProof(
      long tenantId,
      long versionId,
      net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle bundle) {
    var artifactStateResponse = gameDesignClient.getVersionAssetArtifactState(tenantId, versionId);
    if (artifactStateResponse.hasError()) {
      throw new IllegalArgumentException(
          artifactStateResponse.getError().getCode()
              + ": "
              + artifactStateResponse.getError().getMessage());
    }
    var artifactState = artifactStateResponse.getArtifactState();
    if (artifactState.getArtifactState()
            != net.firedevops.firemud.gamedesign.v1.ArtifactState.ARTIFACT_STATE_PUBLISHED
        || !artifactState.getManifestHash().equals(bundle.getManifestHash())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: published asset artifact state does not match the release"
              + " bundle");
    }
    var exportedKeys = new HashSet<>(artifactState.getExportedManifestAssetKeysList());
    if (!exportedKeys.containsAll(bundle.getRequiredManifestAssetKeysList())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: published asset artifact state is missing required"
              + " manifest asset keys");
    }
  }

  private void requireSupportedReleaseAttestationSchema(String schemaVersion) {
    if (!SUPPORTED_RELEASE_ATTESTATION_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "SCHEMA_VERSION_UNSUPPORTED: unsupported published release bundle attestation schema "
              + schemaVersion);
    }
  }

  private PreparedWorldInstance prepareWorldInstance(
      GameInstanceDto startingState,
      ResolvedLaunchDescriptor resolvedLaunchDescriptor,
      StartSessionRequest request) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_PREPARATION_AUTHORITY_UNAVAILABLE);
    }
    final PrepareWorldInstanceResponse response;
    try {
      response =
          worldManagementClient.prepareWorldInstance(
              request.tenantId(),
              startingState.id(),
              request.gameTemplateId(),
              request.controlPlaneRequestId(),
              resolvedLaunchDescriptor.launchDescriptorId(),
              resolvedLaunchDescriptor.versionId(),
              startingState.scriptPatchVersion(),
              resolvedLaunchDescriptor.runtimeFlagsJson(),
              resolvedLaunchDescriptor.generationConfigRevision(),
              resolvedLaunchDescriptor.releaseBundleId(),
              resolvedLaunchDescriptor.publishedReleaseBundleRef(),
              resolvedLaunchDescriptor.versionStateEpoch(),
              resolvedLaunchDescriptor.remapSetId());
    } catch (RuntimeException ex) {
      throw new IllegalStateException(WORLD_PREPARATION_AUTHORITY_UNAVAILABLE, ex);
    }
    if (response == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (response.hasError()) {
      throw new IllegalStateException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    WorldInstanceLifecycleSnapshot snapshot =
        requireWorldSnapshot(
            response.hasWorldInstance(),
            response.getWorldInstance(),
            request.tenantId(),
            startingState.id(),
            WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING);
    return new PreparedWorldInstance(
        request.tenantId(), startingState.id(), snapshot.getLifecycleEpoch());
  }

  private long requirePositiveExternalId(String value, String fieldName) {
    return RequestIdValidation.requirePositiveLong(value, fieldName);
  }

  private void activatePreparedWorldInstance(PreparedWorldInstance preparedWorldInstance) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_ACTIVATION_AUTHORITY_UNAVAILABLE);
    }
    final ActivatePreparedWorldInstanceResponse response;
    try {
      response =
          worldManagementClient.activatePreparedWorldInstance(
              preparedWorldInstance.tenantId(),
              preparedWorldInstance.gameInstanceId(),
              preparedWorldInstance.lifecycleEpoch());
    } catch (RuntimeException ex) {
      throw new IllegalStateException(WORLD_ACTIVATION_AUTHORITY_UNAVAILABLE, ex);
    }
    if (response == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (response.hasError()) {
      throw new IllegalStateException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    requireWorldSnapshot(
        response.hasWorldInstance(),
        response.getWorldInstance(),
        preparedWorldInstance.tenantId(),
        preparedWorldInstance.gameInstanceId(),
        WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE);
  }

  private void failPreparedWorldInstance(
      PreparedWorldInstance preparedWorldInstance, String reason) {
    final FailPreparedWorldInstanceResponse response;
    try {
      response =
          worldManagementClient.failPreparedWorldInstance(
              preparedWorldInstance.tenantId(),
              preparedWorldInstance.gameInstanceId(),
              preparedWorldInstance.lifecycleEpoch(),
              reason);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(WORLD_FAIL_PREPARED_AUTHORITY_UNAVAILABLE, ex);
    }
    if (response == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (response.hasError()) {
      throw new IllegalStateException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    requireWorldSnapshot(
        response.hasWorldInstance(),
        response.getWorldInstance(),
        preparedWorldInstance.tenantId(),
        preparedWorldInstance.gameInstanceId(),
        WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION);
  }

  private WorldInstanceLifecycleSnapshot readWorldInstanceLifecycle(GameInstanceDto runningState) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_LIFECYCLE_AUTHORITY_UNAVAILABLE);
    }
    final GetWorldInstanceLifecycleResponse lifecycleResponse;
    try {
      lifecycleResponse =
          worldManagementClient.getWorldInstanceLifecycle(
              runningState.tenantId(), runningState.id());
    } catch (RuntimeException ex) {
      throw new IllegalStateException(WORLD_LIFECYCLE_AUTHORITY_UNAVAILABLE, ex);
    }
    if (lifecycleResponse == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (lifecycleResponse.hasError()) {
      throw new IllegalStateException(
          lifecycleResponse.getError().getCode()
              + ": "
              + lifecycleResponse.getError().getMessage());
    }
    return requireWorldSnapshot(
        lifecycleResponse.hasWorldInstance(),
        lifecycleResponse.getWorldInstance(),
        runningState.tenantId(),
        runningState.id(),
        null);
  }

  private void requireLifecycleStatus(
      WorldInstanceLifecycleSnapshot lifecycle, WorldInstanceLifecycleStatus expectedStatus) {
    WorldInstanceLifecycleStatus actualStatus = lifecycle.getStatus();
    if (actualStatus == expectedStatus) {
      return;
    }
    if (expectedStatus == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE
        && (actualStatus == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING
            || actualStatus
                == WorldInstanceLifecycleStatus
                    .WORLD_INSTANCE_LIFECYCLE_STATUS_FAILED_PRE_ACTIVATION)) {
      throw new LifecycleOutcomeException(
          "WORLD_INSTANCE_LIFECYCLE_NOT_ACTIVE", "instance is not ACTIVE");
    }
    throw new IllegalStateException(
        "WORLD_AUTHORITY_MALFORMED: lifecycle response has unexpected status");
  }

  private void terminateWorldInstance(
      GameInstanceDto runningState,
      long lifecycleEpoch,
      String terminationRequestId,
      String terminationReason) {
    if (worldManagementClient == null) {
      throw new IllegalStateException(WORLD_TERMINATION_AUTHORITY_UNAVAILABLE);
    }
    final TerminateWorldInstanceResponse terminateResponse;
    try {
      terminateResponse =
          worldManagementClient.terminateWorldInstance(
              runningState.tenantId(),
              runningState.id(),
              lifecycleEpoch,
              terminationRequestId,
              terminationReason);
    } catch (RuntimeException ex) {
      throw new IllegalStateException(WORLD_TERMINATION_AUTHORITY_UNAVAILABLE, ex);
    }
    if (terminateResponse == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    if (terminateResponse.hasError()) {
      throw new IllegalStateException(
          terminateResponse.getError().getCode()
              + ": "
              + terminateResponse.getError().getMessage());
    }
    WorldInstanceLifecycleSnapshot snapshot =
        requireWorldSnapshot(
            terminateResponse.hasWorldInstance(),
            terminateResponse.getWorldInstance(),
            runningState.tenantId(),
            runningState.id(),
            null);
    if (snapshot.getStatus()
        == WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATING) {
      throw new LifecycleOutcomeException(
          "WORLD_TERMINATION_IN_PROGRESS", "session termination is already in progress");
    }
    requireLifecycleStatus(
        snapshot, WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED);
  }

  private WorldInstanceLifecycleSnapshot requireWorldSnapshot(
      boolean present,
      WorldInstanceLifecycleSnapshot snapshot,
      long expectedTenantId,
      long expectedGameInstanceId,
      WorldInstanceLifecycleStatus expectedStatus) {
    if (!present) {
      throw new IllegalStateException(
          "WORLD_AUTHORITY_MALFORMED: response omitted lifecycle snapshot");
    }
    if (snapshot == null) {
      throw new IllegalStateException(WORLD_AUTHORITY_MALFORMED_RESPONSE_NULL);
    }
    final long responseTenantId;
    final long responseGameInstanceId;
    try {
      responseTenantId = requirePositiveExternalId(snapshot.getTenantId(), "tenantId");
      responseGameInstanceId =
          requirePositiveExternalId(snapshot.getGameInstanceId(), "gameInstanceId");
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("WORLD_AUTHORITY_MALFORMED: " + ex.getMessage(), ex);
    }
    if (responseTenantId != expectedTenantId || responseGameInstanceId != expectedGameInstanceId) {
      throw new IllegalStateException(
          "WORLD_AUTHORITY_SCOPE_MISMATCH: lifecycle response does not match the requested"
              + " instance");
    }
    if (snapshot.getLifecycleEpoch() <= 0L) {
      throw new IllegalStateException(
          "WORLD_AUTHORITY_MALFORMED: lifecycle epoch must be positive");
    }
    if (expectedStatus != null) {
      requireLifecycleStatus(snapshot, expectedStatus);
    }
    return snapshot;
  }

  private void runRollbackSafely(String actionName, Runnable rollbackAction) {
    try {
      rollbackAction.run();
    } catch (RuntimeException ex) {
      logger.warn("Failed to roll back {}", actionName, ex);
    }
  }

  private <T> T inTransaction(TransactionSupplier<T> supplier, String actionName) {
    try {
      return transactionOperations.execute(status -> supplier.get());
    } catch (RuntimeException ex) {
      logger.warn("Failed to {}", actionName, ex);
      throw ex;
    }
  }

  private static TransactionOperations immediateTransactionOperations() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(new SimpleTransactionStatus());
      }
    };
  }

  @FunctionalInterface
  private interface TransactionSupplier<T> {
    T get();
  }

  private String releaseBundleRef(long tenantId, long versionId, long releaseBundleId) {
    return "prb:" + tenantId + ":" + versionId + ":" + releaseBundleId;
  }

  private record StartSessionStage(
      GameInstanceDto startingState, @Nullable GameInstanceDto existingRunningState) {}

  private record PreparedWorldInstance(long tenantId, long gameInstanceId, long lifecycleEpoch) {}
}
