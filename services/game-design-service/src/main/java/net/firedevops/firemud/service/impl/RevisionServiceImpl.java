package net.firedevops.firemud.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.RevisionDto;
import net.firedevops.firemud.entity.Game;
import net.firedevops.firemud.entity.Revision;
import net.firedevops.firemud.mapper.RevisionMapper;
import net.firedevops.firemud.repository.GameRepository;
import net.firedevops.firemud.repository.RevisionRepository;
import net.firedevops.firemud.service.RevisionService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RevisionServiceImpl implements RevisionService {
  private static final Logger logger = LoggingUtil.getLogger(RevisionServiceImpl.class);

  private final RevisionRepository revisionRepository;
  private final GameRepository gameRepository;
  private final RevisionMapper revisionMapper;

  @Override
  @Transactional
  public RevisionDto saveRevision(RevisionDto dto) {
    logger.info("Saving revision for tenant {}", dto.tenantId());
    Game game =
        gameRepository
            .findById(dto.tenantId())
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    Revision entity = revisionMapper.toEntity(dto);
    entity.setGame(game);
    entity.setTenantId(game.getTenantId());
    entity = revisionRepository.save(entity);
    return revisionMapper.toDto(entity);
  }
}
