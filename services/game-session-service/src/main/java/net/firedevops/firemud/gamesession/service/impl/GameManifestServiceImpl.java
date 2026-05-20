package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.dto.GameManifestDto;
import net.firedevops.firemud.gamesession.entity.GameManifest;
import net.firedevops.firemud.gamesession.mapper.GameManifestMapper;
import net.firedevops.firemud.gamesession.repository.GameManifestRepository;
import net.firedevops.firemud.gamesession.service.GameManifestService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameManifestServiceImpl implements GameManifestService {
  private static final Logger logger = LoggingUtil.getLogger(GameManifestServiceImpl.class);
  private final GameManifestRepository repository;
  private final GameManifestMapper mapper;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected repository and mapper are retained internal collaborators")
  public GameManifestServiceImpl(GameManifestRepository repository, GameManifestMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Timed(value = "gamesession.manifest.create")
  @Transactional
  public GameManifestDto createManifest(GameManifestDto dto) {
    logger.info("Creating game manifest for version {}", dto.versionId());
    GameManifest entity = mapper.toEntity(dto);
    entity = repository.save(entity);
    return mapper.toDto(entity);
  }
}
