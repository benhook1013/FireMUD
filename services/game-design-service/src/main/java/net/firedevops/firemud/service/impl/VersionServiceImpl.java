package net.firedevops.firemud.service.impl;

import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.client.AutomationScriptingClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.dto.VersionDto;
import net.firedevops.firemud.entity.Game;
import net.firedevops.firemud.entity.Version;
import net.firedevops.firemud.mapper.VersionMapper;
import net.firedevops.firemud.repository.GameRepository;
import net.firedevops.firemud.repository.VersionRepository;
import net.firedevops.firemud.service.VersionService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VersionServiceImpl implements VersionService {
  private static final Logger logger = LoggingUtil.getLogger(VersionServiceImpl.class);

  private final VersionRepository versionRepository;
  private final GameRepository gameRepository;
  private final VersionMapper versionMapper;
  private final AutomationScriptingClient scriptingClient;

  @Override
  @Transactional
  public VersionDto publishVersion(Long gameId, String notes) throws SagaException {
    logger.info("Publishing version for game {}", gameId);
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setGame(game);
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(gameId));

    SagaBuilder builder = new SagaBuilder();
    builder.step(
        "persistVersion",
        () -> {
          versionRepository.save(version);
        },
        () -> versionRepository.delete(version));
    // Steps to copy data to other services would go here
    builder.run();
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional
  public VersionDto publishScriptPatchVersion(
      Long gameId, Long baseVersionId, String scriptPatchVersion, String notes)
      throws SagaException {
    logger.info(
        "Publishing script patch {} for game {} base {}",
        scriptPatchVersion,
        gameId,
        baseVersionId);
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setGame(game);
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(gameId));
    version.setScriptPatchVersion(scriptPatchVersion);
    version.setBaseVersionId(baseVersionId);
    version.setScriptOnly(true);

    SagaBuilder builder = new SagaBuilder();
    builder.step(
        "persistVersion",
        () -> {
          versionRepository.save(version);
        },
        () -> versionRepository.delete(version));
    builder.run();
    scriptingClient.notifyScriptVersionUpdate(gameId, scriptPatchVersion, List.of());
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional(readOnly = true)
  public List<VersionDto> listVersions(Long gameId) {
    Game game =
        gameRepository
            .findById(gameId)
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    return versionRepository.findAll().stream()
        .filter(v -> v.getGame().equals(game))
        .map(versionMapper::toDto)
        .toList();
  }

  private int calculateNextNumber(Long gameId) {
    return (int)
            versionRepository.findAll().stream()
                .filter(v -> v.getGame().getId().equals(gameId))
                .mapToInt(Version::getVersionNumber)
                .max()
                .orElse(0)
        + 1;
  }
}
