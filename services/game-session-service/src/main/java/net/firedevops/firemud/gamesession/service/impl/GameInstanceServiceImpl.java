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
  public GameInstanceDto startSession(StartSessionRequest request) {
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
    repository
        .findFirstByTenantIdAndOwnerAccountIdAndStatus(
            request.tenantId(), request.ownerAccountId(), "RUNNING")
        .ifPresent(
            existing -> {
              logger.info(
                  "Stopping existing session {} for tenant {} owner {}",
                  existing.getId(),
                  existing.getTenantId(),
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
    instance.setStatus("RUNNING");
    GameInstance saved = repository.save(instance);
    GameInstanceDto dto = mapper.toDto(saved);
    sessionStateService.saveState(dto);
    return dto;
  }
}
