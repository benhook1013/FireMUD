package net.firedevops.firemud.gamesession.service.impl;

import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashSet;
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
import net.firedevops.firemud.gamesession.service.SessionStateService;
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
  private static final String SUPPORTED_RELEASE_ATTESTATION_SCHEMA_VERSION = "v1";

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
        long existingWorldLifecycleEpoch = readWorldInstanceLifecycleEpoch(existingRunningState);
        oldWorldTerminationRequested = true;
        terminateWorldInstance(
            existingRunningState,
            existingWorldLifecycleEpoch,
            "session-replace-" + stage.startingState().id() + "-" + UUID.randomUUID());
        oldWorldTerminationCompleted = true;
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

  @Override
  @Timed(value = "gamesession.stop")
  public GameInstanceDto stopSession(long sessionId) {
    GameInstanceDto runningState = inTransaction(() -> stageStopSession(sessionId), "stage stop");
    boolean worldTerminationRequested = false;
    boolean worldTerminationCompleted = false;
    try {
      sessionStateService.deleteState(runningState.tenantId(), runningState.id());
      long worldLifecycleEpoch = readWorldInstanceLifecycleEpoch(runningState);
      worldTerminationRequested = true;
      terminateWorldInstance(runningState, worldLifecycleEpoch, "session-stop-" + UUID.randomUUID());
      worldTerminationCompleted = true;
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
    instance.setScriptPatchVersion(resolvedLaunchDescriptor.scriptPatchVersion());
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

  private GameInstanceDto withStatus(GameInstanceDto snapshot, String status) {
    return new GameInstanceDto(
        snapshot.id(),
        snapshot.tenantId(),
        snapshot.runtimeVersion(),
        snapshot.scriptPatchVersion(),
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
          "RELEASE_ATTESTATION_MISMATCH: resolved launch descriptor does not match the published release bundle");
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
          "VERSION_STATE_EPOCH_STALE: resolved launch descriptor epoch does not match current version state");
    }
    return new ResolvedLaunchDescriptor(
        descriptor.getLaunchDescriptorId(),
        requirePositiveExternalId(descriptor.getTenantId(), "tenantId"),
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
          "RELEASE_ATTESTATION_MISMATCH: published asset artifact state does not match the release bundle");
    }
    var exportedKeys = new HashSet<>(artifactState.getExportedManifestAssetKeysList());
    if (!exportedKeys.containsAll(bundle.getRequiredManifestAssetKeysList())) {
      throw new IllegalArgumentException(
          "RELEASE_ATTESTATION_MISMATCH: published asset artifact state is missing required manifest asset keys");
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
      throw new IllegalStateException("world activation authority unavailable");
    }
    var response =
        worldManagementClient.prepareWorldInstance(
            request.tenantId(),
            startingState.id(),
            request.gameTemplateId(),
            request.controlPlaneRequestId(),
            resolvedLaunchDescriptor.launchDescriptorId(),
            resolvedLaunchDescriptor.versionId(),
            resolvedLaunchDescriptor.scriptPatchVersion(),
            resolvedLaunchDescriptor.runtimeFlagsJson(),
            resolvedLaunchDescriptor.generationConfigRevision(),
            resolvedLaunchDescriptor.releaseBundleId(),
            resolvedLaunchDescriptor.publishedReleaseBundleRef(),
            resolvedLaunchDescriptor.versionStateEpoch(),
            resolvedLaunchDescriptor.remapSetId());
    if (response.hasError()) {
      throw new IllegalArgumentException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    return new PreparedWorldInstance(
        requirePositiveExternalId(response.getWorldInstance().getTenantId(), "tenantId"),
        requirePositiveExternalId(
            response.getWorldInstance().getGameInstanceId(), "gameInstanceId"),
        response.getWorldInstance().getLifecycleEpoch());
  }

  private long requirePositiveExternalId(String value, String fieldName) {
    return RequestIdValidation.requirePositiveLong(value, fieldName);
  }

  private void activatePreparedWorldInstance(PreparedWorldInstance preparedWorldInstance) {
    if (worldManagementClient == null) {
      throw new IllegalStateException("world activation authority unavailable");
    }
    var response =
        worldManagementClient.activatePreparedWorldInstance(
            preparedWorldInstance.tenantId(),
            preparedWorldInstance.gameInstanceId(),
            preparedWorldInstance.lifecycleEpoch());
    if (response.hasError()) {
      throw new IllegalArgumentException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
  }

  private void failPreparedWorldInstance(
      PreparedWorldInstance preparedWorldInstance, String reason) {
    var response =
        worldManagementClient.failPreparedWorldInstance(
            preparedWorldInstance.tenantId(),
            preparedWorldInstance.gameInstanceId(),
            preparedWorldInstance.lifecycleEpoch(),
            reason);
    if (response.hasError()) {
      throw new IllegalArgumentException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
  }

  private long readWorldInstanceLifecycleEpoch(GameInstanceDto runningState) {
    if (worldManagementClient == null) {
      throw new IllegalStateException("world termination authority unavailable");
    }
    var lifecycleResponse =
        worldManagementClient.getWorldInstanceLifecycle(runningState.tenantId(), runningState.id());
    if (lifecycleResponse.hasError()) {
      throw new IllegalArgumentException(
          lifecycleResponse.getError().getCode()
              + ": "
              + lifecycleResponse.getError().getMessage());
    }
    return lifecycleResponse.getWorldInstance().getLifecycleEpoch();
  }

  private void terminateWorldInstance(
      GameInstanceDto runningState, long lifecycleEpoch, String terminationRequestId) {
    if (worldManagementClient == null) {
      throw new IllegalStateException("world termination authority unavailable");
    }
    var terminateResponse =
        worldManagementClient.terminateWorldInstance(
            runningState.tenantId(),
            runningState.id(),
            lifecycleEpoch,
            terminationRequestId,
            "session stop requested");
    if (terminateResponse.hasError()) {
      throw new IllegalArgumentException(
          terminateResponse.getError().getCode()
              + ": "
              + terminateResponse.getError().getMessage());
    }
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
