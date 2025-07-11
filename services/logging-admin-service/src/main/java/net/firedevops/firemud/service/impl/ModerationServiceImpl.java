package net.firedevops.firemud.service.impl;

import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.dto.ModerationActionDto;
import net.firedevops.firemud.entity.ModerationAction;
import net.firedevops.firemud.mapper.ModerationActionMapper;
import net.firedevops.firemud.repository.ModerationActionRepository;
import net.firedevops.firemud.service.ModerationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModerationServiceImpl implements ModerationService {
  private static final Logger logger = LoggingUtil.getLogger(ModerationServiceImpl.class);

  private final ModerationActionRepository repository;
  private final ModerationActionMapper mapper;

  public ModerationServiceImpl(
      ModerationActionRepository repository, ModerationActionMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public ModerationActionDto applyAction(ApplyModerationActionRequest request) {
    logger.info(
        "Applying moderation action {} to account {}", request.action(), request.accountId());
    ModerationAction entity = new ModerationAction();
    entity.setTenantId(request.tenantId());
    entity.setAccountId(request.accountId());
    entity.setAction(request.action());
    entity.setReason(request.reason());
    entity.setCreatedAt(Instant.now());
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }
}
