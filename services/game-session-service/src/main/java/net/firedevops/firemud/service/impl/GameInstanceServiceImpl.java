package net.firedevops.firemud.service.impl;

import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.mapper.GameInstanceMapper;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.GameInstanceService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default implementation of {@link GameInstanceService}. */
@Service
public class GameInstanceServiceImpl implements GameInstanceService {
  private static final Logger logger = LoggingUtil.getLogger(GameInstanceServiceImpl.class);

  private final GameInstanceRepository repository;
  private final GameInstanceMapper mapper;

  public GameInstanceServiceImpl(GameInstanceRepository repository, GameInstanceMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
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
            });
    GameInstance instance = new GameInstance();
    instance.setTenantId(request.tenantId());
    instance.setVersionId(request.versionId());
    instance.setOwnerAccountId(request.ownerAccountId());
    instance.setStatus("RUNNING");
    instance = repository.save(instance);
    return mapper.toDto(instance);
  }

  @Override
  @Transactional
  public GameInstanceDto stopSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus("STOPPED");
    return mapper.toDto(repository.save(instance));
  }

  @Override
  @Transactional
  public GameInstanceDto restartSession(long sessionId) {
    GameInstance instance =
        repository
            .findById(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found"));
    instance.setStatus("RUNNING");
    return mapper.toDto(repository.save(instance));
  }
}
