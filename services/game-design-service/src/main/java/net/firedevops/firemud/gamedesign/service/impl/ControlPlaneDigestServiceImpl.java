package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.service.ControlPlaneDigestService;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected collaborators remain internal service dependencies")
public class ControlPlaneDigestServiceImpl implements ControlPlaneDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 1;

  private final GameTemplateRepository gameTemplateRepository;
  private final GameAssetRepository gameAssetRepository;
  private final RevisionRepository revisionRepository;
  private final ObjectMapper objectMapper;

  public ControlPlaneDigestServiceImpl(
      GameTemplateRepository gameTemplateRepository,
      GameAssetRepository gameAssetRepository,
      RevisionRepository revisionRepository,
      ObjectMapper objectMapper) {
    this.gameTemplateRepository = gameTemplateRepository;
    this.gameAssetRepository = gameAssetRepository;
    this.revisionRepository = revisionRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public DesignControlPlaneDigestDto getDigestForVersion(VersionDto version) {
    return new DesignControlPlaneDigestDto(
        version.tenantId(),
        String.valueOf(version.id()),
        "version:" + version.id(),
        computeDigest(version),
        DIGEST_SCHEMA_VERSION);
  }

  @Override
  public DesignControlPlaneDigestDto getDigestForScriptPatch(VersionDto version) {
    if (version.scriptPatchVersion() == null || version.scriptPatchVersion().isBlank()) {
      throw new IllegalArgumentException("script patch version is required");
    }
    return new DesignControlPlaneDigestDto(
        version.tenantId(),
        version.scriptPatchVersion(),
        "script-patch:" + version.scriptPatchVersion(),
        computeDigest(version),
        DIGEST_SCHEMA_VERSION);
  }

  private String computeDigest(VersionDto version) {
    try {
      List<Map<String, Object>> templates =
          gameTemplateRepository.findByTenantId(version.tenantId(), Pageable.unpaged()).stream()
              .sorted(Comparator.comparing(entity -> entity.getName().toLowerCase()))
              .map(
                  entity ->
                      Map.<String, Object>of(
                          "name", entity.getName(),
                          "description",
                              entity.getDescription() == null ? "" : entity.getDescription(),
                          "config", entity.getConfig()))
              .toList();
      List<Map<String, Object>> assets =
          gameAssetRepository.findByTenantId(version.tenantId()).stream()
              .sorted(Comparator.comparing(entity -> entity.getFileName().toLowerCase()))
              .map(
                  entity ->
                      Map.<String, Object>of(
                          "fileName", entity.getFileName(),
                          "contentType", entity.getContentType(),
                          "byteLength", entity.getData() == null ? 0 : entity.getData().length))
              .toList();
      List<Map<String, Object>> commandDefinitions =
          revisionRepository
              .findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
                  version.tenantId(), version.id(), "COMMAND_DEFINITION")
              .stream()
              .map(
                  revision ->
                      Map.<String, Object>of(
                          "logicalRevisionId",
                          revision.getLogicalRevisionId() == null
                              ? ""
                              : revision.getLogicalRevisionId(),
                          "data",
                          revision.getData()))
              .toList();
      String canonicalJson =
          objectMapper.writeValueAsString(
              Map.of(
                  "tenantId",
                  version.tenantId(),
                  "versionId",
                  version.id(),
                  "versionNumber",
                  version.versionNumber(),
                  "scriptOnly",
                  version.scriptOnly(),
                  "scriptPatchVersion",
                  version.scriptPatchVersion() == null ? "" : version.scriptPatchVersion(),
                  "baseVersionId",
                  version.baseVersionId() == null ? "" : version.baseVersionId(),
                  "notes",
                  version.notes() == null ? "" : version.notes(),
                  "templates",
                  templates,
                  "assets",
                  assets,
                  "commandDefinitions",
                  commandDefinitions));
      return sha256(canonicalJson);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute design control-plane digest", ex);
    }
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
