package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.mapper.RevisionMapper;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.service.RevisionService;
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
  @Timed(value = "gamedesign.revision.save")
  public RevisionDto saveRevision(RevisionDto dto) {
    logger.info("Saving revision for tenant {}", dto.tenantId());
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(dto.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    Revision entity = revisionMapper.toEntity(dto);
    entity.setTenantId(game.getTenantId());
    entity = revisionRepository.save(entity);
    return revisionMapper.toDto(entity);
  }
}
