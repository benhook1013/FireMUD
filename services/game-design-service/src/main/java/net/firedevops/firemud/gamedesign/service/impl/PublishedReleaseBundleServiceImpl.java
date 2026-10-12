package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.PublishedArtifactDigest;
import net.firedevops.firemud.gamedesign.service.PublishedReleaseBundleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Fail-fast startup is intentional if bundle persistence wiring is invalid.")
public class PublishedReleaseBundleServiceImpl implements PublishedReleaseBundleService {

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
    throw new IllegalStateException(
        "PUBLISH_SELECTION_REQUIRED: full-version bundles require the captured publication operation");
  }

  @Override
  @Transactional(readOnly = true)
  public PublishedReleaseBundleDto getPublishedReleaseBundle(String tenantId, long versionId) {
    return findPublishedReleaseBundle(tenantId, versionId)
        .orElseThrow(() -> new PublishedReleaseBundleNotFoundException(tenantId, versionId));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PublishedReleaseBundleDto> findPublishedReleaseBundle(
      String tenantId, long versionId) {
    return repository.findByTenantIdAndVersionId(tenantId, versionId).map(this::toDto);
  }

  private PublishedReleaseBundleDto toDto(PublishedReleaseBundle entity) {
    PublishedReleaseBundleDto dto =
        new PublishedReleaseBundleDto(
            entity.getId(),
            entity.getTenantId(),
            entity.getVersionId(),
            entity.getVersionNumber(),
            entity.getAttestationSchemaVersion(),
            entity.getPublishWorkflowId(),
            entity.getManifestHash(),
            deserializeKeys(entity.getRequiredManifestAssetKeysJson()),
            deserializeParticipantDigests(entity.getParticipantDigestsJson()),
            deserializeCommandDefinitions(entity.getCommandDefinitionsJson()),
            entity.getGenerationConfigRevision(),
            entity.isScriptOnly(),
            entity.getScriptPatchVersion(),
            entity.getPublishedAt(),
            entity.getCanonicalTenantId(),
            entity.getCanonicalVersionId(),
            entity.getPublishedReleaseBundleRef(),
            entity.getManifestSchemaVersion(),
            deserializeArtifactDigests(entity.getArtifactDigestsJson()),
            entity.getWorldPublishedStartLocationEvidenceJson() == null
                ? null
                : WorldPublishedStartLocationEvidence.fromStored(
                    entity
                        .getWorldPublishedStartLocationEvidenceJson()
                        .getBytes(StandardCharsets.UTF_8)));
    if (PublishedReleaseBundleContract.SELECTOR_ATTESTATION_SCHEMA_VERSION.equals(
        dto.attestationSchemaVersion())) {
      PublishedReleaseBundleContract.requireSelectorBinding(dto);
    }
    return dto;
  }

  private List<PublishedArtifactDigest> deserializeArtifactDigests(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return objectMapper.readValue(
        json,
        objectMapper
            .getTypeFactory()
            .constructCollectionType(List.class, PublishedArtifactDigest.class));
  }

  private List<String> deserializeKeys(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValue(
        json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  private List<String> deserializeCommandDefinitions(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValue(
        json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
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
