package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed")
public class VersionServiceImpl implements VersionService {
  private static final Logger logger = LoggingUtil.getLogger(VersionServiceImpl.class);

  private final VersionRepository versionRepository;
  private final GameRepository gameRepository;
  private final VersionMapper versionMapper;
  private final AutomationScriptingClient scriptingClient;
  private final SagaRunner sagaRunner;
  private final AssetExportService assetExportService;

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publish")
  public VersionDto publishVersion(String tenantId, String notes) throws SagaException {
    logger.info("Publishing version for tenant {}", tenantId);
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(tenantId))
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
    builder.step(
        "exportAssets",
        () -> assetExportService.exportAssets(tenantId, version.getVersionNumber()),
        () -> assetExportService.deleteExportedAssets(tenantId, version.getVersionNumber()));
    sagaRunner.run(builder.build());
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publishScriptPatch")
  public VersionDto publishScriptPatchVersion(
      String tenantId, Long baseVersionId, String scriptPatchVersion, String notes)
      throws SagaException {
    logger.info(
        "Publishing script patch {} for tenant {} base {}",
        scriptPatchVersion,
        tenantId,
        baseVersionId);
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(tenantId))
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
  public List<VersionDto> listVersions(String tenantId) {
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    return versionRepository.findAll().stream()
        .filter(v -> v.getGame().equals(game))
        .map(versionMapper::toDto)
        .toList();
  }

  private int calculateNextNumber(String tenantId) {
    return (int)
            versionRepository.findAll().stream()
                .filter(v -> v.getTenantId().equals(tenantId))
                .mapToInt(Version::getVersionNumber)
                .max()
                .orElse(0)
        + 1;
  }
}
