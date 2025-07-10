package net.firedevops.firemud.service.impl;

import net.firedevops.firemud.common.LoggingUtil;
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

  public GameInstanceServiceImpl(
      GameInstanceRepository repository,
      GameInstanceMapper mapper,
      SessionStateService sessionStateService) {
    this.repository = repository;
    this.mapper = mapper;
    this.sessionStateService = sessionStateService;
  }

  @Override
  @Transactional
  public GameInstanceDto startSession(StartSessionRequest request) {
    logger.info(
        "Starting game session for tenant {} version {}", request.tenantId(), request.versionId());
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
    instance.setVersionId(request.versionId());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus("RUNNING");
    instance = repository.save(instance);
    GameInstanceDto dto = mapper.toDto(instance);
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
