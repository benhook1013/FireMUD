package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.client.AutomationScriptingClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
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
  private final SagaRunner sagaRunner;

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publish")
  public VersionDto publishVersion(Long tenantId, String notes) throws SagaException {
    logger.info("Publishing version for tenant {}", tenantId);
    Game game =
        gameRepository
            .findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setGame(game);
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(tenantId));

    SagaBuilder builder = new SagaBuilder("publishVersion");
    builder.step(
        "persistVersion",
        () -> {
          versionRepository.save(version);
        },
        () -> versionRepository.delete(version));
    // Steps to copy data to other services would go here
    sagaRunner.run(builder.build());
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publishScriptPatch")
  public VersionDto publishScriptPatchVersion(
      Long tenantId, Long baseVersionId, String scriptPatchVersion, String notes)
      throws SagaException {
    logger.info(
        "Publishing script patch {} for tenant {} base {}",
        scriptPatchVersion,
        tenantId,
        baseVersionId);
    Game game =
        gameRepository
            .findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setGame(game);
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(tenantId));
    version.setScriptPatchVersion(scriptPatchVersion);
    version.setBaseVersionId(baseVersionId);
    version.setScriptOnly(true);

    SagaBuilder builder = new SagaBuilder("publishScriptPatch");
    builder.step(
        "persistVersion",
        () -> {
          versionRepository.save(version);
        },
        () -> versionRepository.delete(version));
    sagaRunner.run(builder.build());
    scriptingClient.notifyScriptVersionUpdate(
        String.valueOf(game.getTenantId()), scriptPatchVersion, List.of());
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "gamedesign.version.list")
  public List<VersionDto> listVersions(Long tenantId) {
    Game game =
        gameRepository
            .findById(tenantId)
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    return versionRepository.findAll().stream()
        .filter(v -> v.getGame().equals(game))
        .map(versionMapper::toDto)
        .toList();
  }

  private int calculateNextNumber(Long tenantId) {
    return (int)
            versionRepository.findAll().stream()
                .filter(v -> v.getGame().getId().equals(tenantId))
                .mapToInt(Version::getVersionNumber)
                .max()
                .orElse(0)
        + 1;
  }
}
