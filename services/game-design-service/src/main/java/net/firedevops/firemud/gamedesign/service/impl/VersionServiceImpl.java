package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.client.AutomationScriptingClient;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected dependencies are not exposed")
public class VersionServiceImpl implements VersionService {
  private static final Logger logger = LoggingUtil.getLogger(VersionServiceImpl.class);

  private final VersionRepository versionRepository;
  private final GameRepository gameRepository;
  private final VersionMapper versionMapper;
  private final AutomationScriptingClient scriptingClient;
  private final AssetExportService assetExportService;

  @Autowired
  public VersionServiceImpl(
      VersionRepository versionRepository,
      GameRepository gameRepository,
      VersionMapper versionMapper,
      AutomationScriptingClient scriptingClient,
      AssetExportService assetExportService) {
    this.versionRepository = versionRepository;
    this.gameRepository = gameRepository;
    this.versionMapper = versionMapper;
    this.scriptingClient = scriptingClient;
    this.assetExportService = assetExportService;
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publish")
  public VersionDto publishVersion(String tenantId, String notes) {
    logger.info("Publishing version for tenant {}", tenantId);
    Game game =
        Optional.ofNullable(gameRepository.findByTenantIdForUpdate(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(tenantId));
    versionRepository.save(version);
    runAfterCommit(
        "export version assets",
        () -> assetExportService.exportAssets(tenantId, version.getVersionNumber()));
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.version.publishScriptPatch")
  public VersionDto publishScriptPatchVersion(
      String tenantId, Long baseVersionId, String scriptPatchVersion, String notes) {
    logger.info(
        "Publishing script patch {} for tenant {} base {}",
        scriptPatchVersion,
        tenantId,
        baseVersionId);
    Game game =
        Optional.ofNullable(gameRepository.findByTenantIdForUpdate(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));

    Version version = new Version();
    version.setTenantId(game.getTenantId());
    version.setNotes(notes);
    version.setVersionNumber(calculateNextNumber(tenantId));
    version.setScriptPatchVersion(scriptPatchVersion);
    version.setBaseVersionId(baseVersionId);
    version.setScriptOnly(true);

    versionRepository.save(version);
    runAfterCommit(
        "notify script patch version update",
        () ->
            scriptingClient.notifyScriptVersionUpdate(
                String.valueOf(game.getTenantId()), scriptPatchVersion, List.of()));
    return versionMapper.toDto(version);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "gamedesign.version.list")
  public List<VersionDto> listVersions(String tenantId) {
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(tenantId))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    return versionRepository.findAllByTenantIdOrderByVersionNumberAsc(game.getTenantId()).stream()
        .map(versionMapper::toDto)
        .toList();
  }

  private int calculateNextNumber(String tenantId) {
    return versionRepository
            .findTopByTenantIdOrderByVersionNumberDesc(tenantId)
            .map(Version::getVersionNumber)
            .orElse(0)
        + 1;
  }

  private void runAfterCommit(String actionName, Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      runSafely(actionName, action);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            runSafely(actionName, action);
          }
        });
  }

  private void runSafely(String actionName, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException ex) {
      logger.warn("Failed to {}", actionName, ex);
    }
  }
}
