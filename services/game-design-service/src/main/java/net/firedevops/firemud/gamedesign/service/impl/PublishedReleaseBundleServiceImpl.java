package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
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
  private final RevisionRepository revisionRepository;
  private final VersionRepository versionRepository;
  private final ObjectMapper objectMapper;

  public PublishedReleaseBundleServiceImpl(
      PublishedReleaseBundleRepository repository,
      RevisionRepository revisionRepository,
      VersionRepository versionRepository,
      ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.revisionRepository =
        Objects.requireNonNull(revisionRepository, "revisionRepository must not be null");
    this.versionRepository =
        Objects.requireNonNull(versionRepository, "versionRepository must not be null");
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

  private PublishedReleaseBundleDto createBundle(
      VersionDto version,
      String publishWorkflowId,
      ExportedAssetManifest exportedManifest,
      String generationConfigRevision,
      List<PublishParticipantDigestDto> participantDigests) {
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(exportedManifest, "exportedManifest must not be null");
    Objects.requireNonNull(participantDigests, "participantDigests must not be null");
    Optional<PublishedReleaseBundle> existing =
        repository.findByTenantIdAndVersionId(version.tenantId(), version.id());
    if (existing.isPresent()) {
      throw new IllegalStateException("published release bundle already exists");
    }
    var identitySource =
        versionRepository
            .findByTenantIdAndId(version.tenantId(), version.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "published release canonical identity source missing"));
    UUID canonicalTenantId = identitySource.getCanonicalTenantId();
    UUID canonicalVersionId = identitySource.getCanonicalVersionId();
    if (!version.id().equals(identitySource.getId())
        || !version.tenantId().equals(identitySource.getTenantId())
        || !isCanonicalNonNilUuid(canonicalTenantId)
        || !isCanonicalNonNilUuid(canonicalVersionId)) {
      throw new IllegalStateException("published release canonical identity source is invalid");
    }
    PublishedReleaseBundle entity = new PublishedReleaseBundle();
    entity.setTenantId(version.tenantId());
    entity.setVersionId(version.id());
    entity.setCanonicalTenantId(canonicalTenantId);
    entity.setCanonicalVersionId(canonicalVersionId);
    entity.setVersionNumber(version.versionNumber());
    entity.setAttestationSchemaVersion(
        PublishedReleaseBundleContract.SUPPORTED_ATTESTATION_SCHEMA_VERSION);
    entity.setPublishWorkflowId(publishWorkflowId);
    entity.setManifestHash(exportedManifest.manifestHash());
    entity.setManifestSchemaVersion(exportedManifest.manifestSchemaVersion());
    entity.setArtifactDigestsJson(
        objectMapper.writeValueAsString(exportedManifest.artifactDigests()));
    entity.setGenerationConfigRevision(generationConfigRevision);
    entity.setRequiredManifestAssetKeysJson(
        serializeKeys(exportedManifest.requiredManifestAssetKeys()));
    entity.setParticipantDigestsJson(serializeParticipantDigests(participantDigests));
    List<String> commandDefinitions =
        revisionRepository
            .findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
                version.tenantId(), version.id(), "COMMAND_DEFINITION")
            .stream()
            .map(revision -> revision.getData())
            .toList();
    validateDistinctCommandDefinitions(commandDefinitions);
    entity.setCommandDefinitionsJson(serializeCommandDefinitions(commandDefinitions));
    entity.setScriptOnly(version.scriptOnly());
    entity.setScriptPatchVersion(version.scriptPatchVersion());
    return toDto(repository.save(entity));
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
            entity.getArtifactDigestsJson() == null
                ? null
                : objectMapper.readValue(
                    entity.getArtifactDigestsJson(),
                    objectMapper
                        .getTypeFactory()
                        .constructCollectionType(List.class, PublishedArtifactDigest.class)),
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

  private boolean isCanonicalNonNilUuid(UUID value) {
    return value != null && !value.equals(new UUID(0L, 0L));
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

  private String serializeCommandDefinitions(List<String> commandDefinitions) {
    return objectMapper.writeValueAsString(
        commandDefinitions == null ? List.of() : commandDefinitions);
  }

  private void validateDistinctCommandDefinitions(List<String> commandDefinitions) {
    Map<String, String> tokenOwners = new HashMap<>();
    Set<String> commandIds = new HashSet<>();
    for (String commandDefinition : commandDefinitions) {
      var definition = objectMapper.readTree(commandDefinition);
      CommandEffectDeclarationValidator.validateAll(definition.path("effects"));
      String commandId = normalizeCommandToken(definition.path("commandId").asText());
      if (!commandIds.add(commandId)) {
        throw new IllegalStateException("duplicate published commandDefinition id " + commandId);
      }
      ensureSingleTokenOwner(tokenOwners, commandId, commandId);
      for (var alias : definition.path("aliases")) {
        String normalizedAlias = normalizeCommandToken(alias.asText());
        ensureSingleTokenOwner(tokenOwners, normalizedAlias, commandId);
      }
    }
  }

  private void ensureSingleTokenOwner(
      Map<String, String> tokenOwners, String token, String commandId) {
    String existingOwner = tokenOwners.putIfAbsent(token, commandId);
    if (existingOwner != null && !existingOwner.equals(commandId)) {
      throw new IllegalStateException("ambiguous published commandDefinition token " + token);
    }
  }

  private String normalizeCommandToken(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("published commandDefinition token must not be blank");
    }
    return value.trim().toLowerCase(Locale.ROOT);
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
