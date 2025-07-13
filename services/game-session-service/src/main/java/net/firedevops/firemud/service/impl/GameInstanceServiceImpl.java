package net.firedevops.firemud.service.impl;

import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.client.EntityManagementClient;
import net.firedevops.firemud.client.GameLogicClient;
import net.firedevops.firemud.client.WorldManagementClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.mapper.GameInstanceMapper;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.GameInstanceService;
import net.firedevops.firemud.service.SessionStateService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link GameInstanceService}. */
@Service
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

  public GameInstanceServiceImpl(
      GameInstanceRepository repository,
      GameInstanceMapper mapper,
      SessionStateService sessionStateService,
      GameLogicClient gameLogicClient,
      WorldManagementClient worldManagementClient,
      EntityManagementClient entityManagementClient,
      SagaRunner sagaRunner,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.mapper = mapper;
    this.sessionStateService = sessionStateService;
    this.gameLogicClient = gameLogicClient;
    this.worldManagementClient = worldManagementClient;
    this.entityManagementClient = entityManagementClient;
    this.sagaRunner = sagaRunner;
    this.meterRegistry = meterRegistry;
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
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  @Override
  @Transactional
  public GameInstanceDto startSession(StartSessionRequest request) {
    logger.info(
        "Starting game session for tenant {} version {} patch {}",
        request.tenantId(),
        request.runtimeVersion(),
        request.scriptPatchVersion());
    repository
        .findFirstByOwnerAccountIdAndStatus(request.ownerAccountId(), "RUNNING")
        .ifPresent(
            existing -> {
              logger.info(
                  "Stopping existing session {} for owner {}",
                  existing.getId(),
                  existing.getOwnerAccountId());
              existing.setStatus("STOPPED");
              repository.save(existing);
              sessionStateService.deleteState(existing.getTenantId(), existing.getId());
            });
    GameInstance instance = new GameInstance();
    instance.setTenantId(request.tenantId());
    instance.setRuntimeVersion(request.runtimeVersion());
    instance.setScriptPatchVersion(request.scriptPatchVersion());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus("RUNNING");
    instance = repository.save(instance);
    GameInstanceDto dto = mapper.toDto(instance);
    meterRegistry
        .counter(
            "game_sessions_started_total",
            "script_patch_version",
            request.scriptPatchVersion() == null ? "none" : request.scriptPatchVersion())
        .increment();

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
      try {
        sagaRunner.run(saga);
      } catch (SagaException e) {
        logger.error("Saga failed during session start", e);
        throw new IllegalStateException("Failed to start session", e);
      }
    }

    sessionStateService.saveState(dto);
    return dto;
  }

  @Override
  @Transactional
  public GameInstanceDto stopSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus("STOPPED");
    GameInstance saved = repository.save(instance);
    sessionStateService.deleteState(instance.getTenantId(), instance.getId());

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
      try {
        sagaRunner.run(saga);
      } catch (SagaException e) {
        logger.error("Saga failed during session stop", e);
        throw new IllegalStateException("Failed to stop session", e);
      }
    }

    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public GameInstanceDto restartSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus("RUNNING");
    GameInstance saved = repository.save(instance);
    GameInstanceDto dto = mapper.toDto(saved);
    sessionStateService.saveState(dto);
    return dto;
  }
}
