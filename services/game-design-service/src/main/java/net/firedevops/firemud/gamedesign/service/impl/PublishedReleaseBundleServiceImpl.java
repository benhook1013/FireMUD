package net.firedevops.firemud.gamedesign.service.impl;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PublishedReleaseBundleServiceImpl implements PublishedReleaseBundleService {
  private static final String ATTESTATION_SCHEMA_VERSION = "v1";

  private final PublishedReleaseBundleRepository repository;
  private final ObjectMapper objectMapper;

  public PublishedReleaseBundleServiceImpl(
      PublishedReleaseBundleRepository repository, ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @Override
  @Transactional
  public PublishedReleaseBundleDto createFullVersionBundle(
      VersionDto version,
      String publishWorkflowId,
      ExportedAssetManifest exportedManifest,
      String generationConfigRevision,
      List<PublishParticipantDigestDto> participantDigests) {
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(exportedManifest, "exportedManifest must not be null");
    Objects.requireNonNull(participantDigests, "participantDigests must not be null");
    repository
        .findByTenantIdAndVersionId(version.tenantId(), version.id())
        .ifPresent(
            ignored -> {
              throw new IllegalStateException("published release bundle already exists");
            });
    PublishedReleaseBundle entity = new PublishedReleaseBundle();
    entity.setTenantId(version.tenantId());
    entity.setVersionId(version.id());
    entity.setVersionNumber(version.versionNumber());
    entity.setAttestationSchemaVersion(ATTESTATION_SCHEMA_VERSION);
    entity.setPublishWorkflowId(publishWorkflowId);
    entity.setManifestHash(exportedManifest.manifestHash());
    entity.setGenerationConfigRevision(generationConfigRevision);
    entity.setRequiredManifestAssetKeysJson(
        serializeKeys(exportedManifest.requiredManifestAssetKeys()));
    entity.setParticipantDigestsJson(serializeParticipantDigests(participantDigests));
    entity.setScriptOnly(version.scriptOnly());
    entity.setScriptPatchVersion(version.scriptPatchVersion());
    return toDto(repository.save(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId) {
    return repository
        .findByTenantIdAndVersionId(tenantId, versionId)
        .map(this::toDto)
        .orElseThrow(() -> new IllegalArgumentException("published release bundle not found"));
  }

  private PublishedReleaseBundleDto toDto(PublishedReleaseBundle entity) {
    return new PublishedReleaseBundleDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getVersionId(),
        entity.getVersionNumber(),
        entity.getAttestationSchemaVersion(),
        entity.getPublishWorkflowId(),
        entity.getManifestHash(),
        deserializeKeys(entity.getRequiredManifestAssetKeysJson()),
        deserializeParticipantDigests(entity.getParticipantDigestsJson()),
        entity.getGenerationConfigRevision(),
        entity.isScriptOnly(),
        entity.getScriptPatchVersion(),
        entity.getPublishedAt());
  }

  private String serializeKeys(List<String> keys) {
    return objectMapper.writeValueAsString(keys == null ? List.of() : List.copyOf(keys));
  }

  private List<String> deserializeKeys(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValue(
        json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  private String serializeParticipantDigests(List<PublishParticipantDigestDto> participantDigests) {
    return objectMapper.writeValueAsString(
        participantDigests == null ? List.of() : List.copyOf(participantDigests));
  }

  private List<PublishParticipantDigestDto> deserializeParticipantDigests(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValue(
        json,
        objectMapper
            .getTypeFactory()
            .constructCollectionType(List.class, PublishParticipantDigestDto.class));
  }
}
