package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionDto;
import net.firedevops.firemud.gamedesign.entity.PublishedRealmEntryPolicy;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.repository.PublishedRealmEntryPolicyRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
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
  private final PublishedRealmEntryPolicyRepository realmEntryPolicyRepository;
  private final GameRepository gameRepository;
  private final VersionRepository versionRepository;
  private final ObjectMapper objectMapper;

  public PublishedReleaseBundleServiceImpl(
      PublishedReleaseBundleRepository repository,
      RevisionRepository revisionRepository,
      PublishedRealmEntryPolicyRepository realmEntryPolicyRepository,
      GameRepository gameRepository,
      VersionRepository versionRepository,
      ObjectMapper objectMapper) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.revisionRepository =
        Objects.requireNonNull(revisionRepository, "revisionRepository must not be null");
    this.realmEntryPolicyRepository =
        Objects.requireNonNull(
            realmEntryPolicyRepository, "realmEntryPolicyRepository must not be null");
    this.gameRepository = Objects.requireNonNull(gameRepository, "gameRepository must not be null");
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
    Objects.requireNonNull(version, "version must not be null");
    Objects.requireNonNull(exportedManifest, "exportedManifest must not be null");
    Objects.requireNonNull(participantDigests, "participantDigests must not be null");
    List<RealmEntryPolicySource> realmPolicies = prepareRealmEntryPolicies(version);
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
    entity.setAttestationSchemaVersion(
        PublishedReleaseBundleContract.SUPPORTED_ATTESTATION_SCHEMA_VERSION);
    entity.setPublishWorkflowId(publishWorkflowId);
    entity.setManifestHash(exportedManifest.manifestHash());
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
    PublishedReleaseBundle saved = repository.save(entity);
    freezeRealmEntryPolicies(version, saved, realmPolicies);
    return toDto(saved);
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

  @Override
  @Transactional(readOnly = true)
  public PublishedRealmEntryPolicyEvidence resolvePublishedRealmEntryPolicy(
      UUID canonicalTenantId, long versionId, String worldSlug, String realmSlug) {
    requirePolicyReadRequest(canonicalTenantId, versionId);
    if (!RealmEntryPolicy.isCanonicalSlug(worldSlug)
        || !RealmEntryPolicy.isCanonicalSlug(realmSlug)) {
      throw new IllegalArgumentException("Published realm-entry policy selector is invalid");
    }
    PolicyOwnerContext owner = resolvePolicyOwnerContext(canonicalTenantId, versionId);
    List<PublishedRealmEntryPolicy> policies =
        realmEntryPolicyRepository.findByScope(canonicalTenantId, versionId, worldSlug, realmSlug);
    if (policies.isEmpty()) {
      throw new PublishedRealmEntryPolicyNotFoundException();
    }
    if (policies.size() != 1) {
      throw new IllegalStateException("Published realm-entry policy selector is ambiguous");
    }
    return validateStoredPolicy(policies.getFirst(), owner);
  }

  @Override
  @Transactional(readOnly = true)
  public PublishedRealmEntryPolicySetEvidence listPublishedRealmEntryPolicies(
      UUID canonicalTenantId, long versionId) {
    requirePolicyReadRequest(canonicalTenantId, versionId);
    PolicyOwnerContext owner = resolvePolicyOwnerContext(canonicalTenantId, versionId);
    List<PublishedRealmEntryPolicy> storedPolicies =
        realmEntryPolicyRepository.findByOwner(canonicalTenantId, versionId);
    if (storedPolicies.isEmpty()) {
      throw new PublishedRealmEntryPolicyNotFoundException();
    }
    if (storedPolicies.size() > PublishedRealmEntryPolicySetEvidence.MAX_POLICIES) {
      throw new IllegalStateException(
          "Published realm-entry policy set exceeds the complete-read bound");
    }
    List<PublishedRealmEntryPolicyEvidence> policies =
        storedPolicies.stream().map(policy -> validateStoredPolicy(policy, owner)).toList();
    PublishedRealmEntryPolicySetEvidence set =
        PublishedRealmEntryPolicySetEvidence.create(
            canonicalTenantId,
            versionId,
            owner.version().getVersionNumber(),
            owner.releaseBundleIdentity(),
            owner.bundle().getPublishWorkflowId(),
            owner.bundle().getManifestHash(),
            policies,
            objectMapper);
    if (!set.hasValidDigest(objectMapper)) {
      throw new IllegalStateException(
          "Published realm-entry policy set digest does not match its evidence");
    }
    return set;
  }

  private PolicyOwnerContext resolvePolicyOwnerContext(UUID canonicalTenantId, long versionId) {
    GameTenantIdentity identity =
        gameRepository
            .findRuntimeTenantIdentityByCanonicalTenantId(canonicalTenantId)
            .orElseThrow(PublishedRealmEntryPolicyNotFoundException::new);
    validateTenantIdentity(identity, canonicalTenantId, identity.sourceLegacyTenantId());
    var version =
        versionRepository
            .findByTenantIdAndId(identity.sourceLegacyTenantId(), versionId)
            .orElseThrow(PublishedRealmEntryPolicyNotFoundException::new);
    if (!canonicalTenantId.equals(identity.canonicalTenantId())
        || !identity.sourceLegacyTenantId().equals(version.getTenantId())
        || !versionIdEquals(versionId, version.getId())
        || (version.getVersionState() != VersionLifecycleState.PUBLISHED
            && version.getVersionState() != VersionLifecycleState.ACTIVE)) {
      throw new IllegalStateException(
          "Published realm-entry policy version evidence is inconsistent");
    }
    if (version.isScriptOnly()) {
      throw new IllegalStateException("Script-only version cannot attest a realm-entry policy");
    }

    PublishedReleaseBundle bundle =
        repository
            .findByTenantIdAndVersionId(identity.sourceLegacyTenantId(), versionId)
            .orElseThrow(PublishedRealmEntryPolicyNotFoundException::new);
    if (bundle.getId() == null
        || bundle.getId() <= 0
        || !identity.sourceLegacyTenantId().equals(bundle.getTenantId())
        || !versionIdEquals(versionId, bundle.getVersionId())
        || version.getVersionNumber() != bundle.getVersionNumber()
        || !PublishedReleaseBundleContract.SUPPORTED_ATTESTATION_SCHEMA_VERSION.equals(
            bundle.getAttestationSchemaVersion())
        || bundle.isScriptOnly()) {
      throw new IllegalStateException("Published release bundle evidence is inconsistent");
    }
    String releaseBundleIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            canonicalTenantId,
            versionId,
            bundle.getPublishWorkflowId(),
            bundle.getManifestHash(),
            objectMapper);
    return new PolicyOwnerContext(identity, version, bundle, releaseBundleIdentity);
  }

  private PublishedRealmEntryPolicyEvidence validateStoredPolicy(
      PublishedRealmEntryPolicy stored, PolicyOwnerContext owner) {
    if (stored == null) {
      throw new IllegalStateException("Published realm-entry policy row is missing");
    }
    GameTenantIdentity identity = owner.tenantIdentity();
    var version = owner.version();
    PublishedReleaseBundle bundle = owner.bundle();
    Revision source = revisionRepository.findById(stored.sourceRevisionId());
    if (source == null
        || source.getId() == null
        || source.getId() <= 0
        || !identity.sourceLegacyTenantId().equals(source.getTenantId())
        || !versionIdEquals(version.getId(), source.getVersionId())
        || !RealmEntryPolicy.REVISION_KIND.equals(source.getRevisionKind())) {
      throw new IllegalStateException(
          "Published realm-entry policy source revision is inconsistent");
    }

    RealmEntryPolicy frozenPolicy =
        RealmEntryPolicy.parseCanonical(stored.policyJson(), objectMapper);
    RealmEntryPolicy sourcePolicy = RealmEntryPolicy.parse(source.getData(), objectMapper);
    if (!frozenPolicy.equals(sourcePolicy)
        || !matchesStoredPolicyFields(stored, frozenPolicy)
        || !identity.canonicalTenantId().equals(stored.canonicalTenantId())
        || !Objects.equals(identity.provenanceKind().name(), stored.tenantIdentityProvenanceKind())
        || !Objects.equals(identity.sourceGameId(), stored.sourceGameRowId())
        || !Objects.equals(identity.sourceLegacyTenantId(), stored.sourceGameTenantKey())
        || !Objects.equals(version.getId(), stored.versionId())
        || version.getVersionNumber() != stored.versionNumber()
        || !Objects.equals(bundle.getId(), stored.releaseBundleId())
        || !Objects.equals(source.getId(), stored.sourceRevisionId())
        || !Objects.equals(owner.releaseBundleIdentity(), stored.releaseBundleIdentity())
        || !Objects.equals(bundle.getPublishWorkflowId(), stored.publishWorkflowId())
        || !Objects.equals(bundle.getManifestHash(), stored.manifestHash())) {
      throw new IllegalStateException(
          "Published realm-entry policy evidence contradicts its owners");
    }

    PublishedRealmEntryPolicyEvidence evidence =
        new PublishedRealmEntryPolicyEvidence(
            stored.policyId(),
            stored.canonicalTenantId(),
            stored.tenantIdentityProvenanceKind(),
            stored.sourceGameRowId(),
            stored.sourceGameTenantKey(),
            stored.versionId(),
            stored.versionNumber(),
            stored.sourceRevisionId(),
            stored.releaseBundleIdentity(),
            stored.publishWorkflowId(),
            stored.manifestHash(),
            frozenPolicy,
            stored.policyDigest());
    if (!evidence.hasValidDigest(objectMapper)) {
      throw new IllegalStateException(
          "Published realm-entry policy digest does not match its evidence");
    }
    return evidence;
  }

  private void requirePolicyReadRequest(UUID canonicalTenantId, long versionId) {
    if (canonicalTenantId == null || new UUID(0L, 0L).equals(canonicalTenantId) || versionId <= 0) {
      throw new IllegalArgumentException("Published realm-entry policy owner scope is invalid");
    }
  }

  private List<RealmEntryPolicySource> prepareRealmEntryPolicies(VersionDto version) {
    List<Revision> revisions =
        revisionRepository.findByTenantIdAndVersionIdAndRevisionKindOrderByIdAsc(
            version.tenantId(), version.id(), RealmEntryPolicy.REVISION_KIND);
    if (revisions.isEmpty()) {
      return List.of();
    }
    if (revisions.size() > PublishedRealmEntryPolicySetEvidence.MAX_POLICIES) {
      throw new IllegalStateException("Realm-entry policy set exceeds the v1 complete-read bound");
    }
    if (version.scriptOnly()) {
      throw new IllegalStateException("Script-only publication cannot freeze realm-entry policy");
    }
    if (version.id() == null || version.id() <= 0 || version.versionNumber() <= 0) {
      throw new IllegalStateException("Realm-entry policy requires an exact owner version");
    }

    Set<String> selectorPairs = new HashSet<>();
    Set<String> realmSlugs = new HashSet<>();
    List<RealmEntryPolicySource> sources = new java.util.ArrayList<>();
    int visiblePublicProductionCount = 0;
    for (Revision revision : revisions) {
      if (revision == null
          || revision.getId() == null
          || revision.getId() <= 0
          || !Objects.equals(version.tenantId(), revision.getTenantId())
          || !Objects.equals(version.id(), revision.getVersionId())
          || !RealmEntryPolicy.REVISION_KIND.equals(revision.getRevisionKind())) {
        throw new IllegalStateException(
            "Realm-entry policy source revision is outside its owner scope");
      }
      RealmEntryPolicy policy = RealmEntryPolicy.parse(revision.getData(), objectMapper);
      String pair = policy.worldSlug() + "\u0000" + policy.realmSlug();
      if (!selectorPairs.add(pair)) {
        throw new IllegalStateException("Duplicate published realm-entry policy selector");
      }
      if (!realmSlugs.add(policy.realmSlug())) {
        throw new IllegalStateException("Duplicate published realm-entry policy realm slug");
      }
      if (policy.visible() && policy.publicProduction()) {
        visiblePublicProductionCount++;
      }
      sources.add(new RealmEntryPolicySource(revision, policy));
    }
    if (visiblePublicProductionCount != 1) {
      throw new IllegalStateException(
          "Realm-entry policy set must contain exactly one visible publicProduction realm");
    }
    return List.copyOf(sources);
  }

  private void freezeRealmEntryPolicies(
      VersionDto version, PublishedReleaseBundle bundle, List<RealmEntryPolicySource> sources) {
    if (sources.isEmpty()) {
      return;
    }
    if (bundle == null
        || bundle.getId() == null
        || bundle.getId() <= 0
        || !Objects.equals(version.tenantId(), bundle.getTenantId())
        || !Objects.equals(version.id(), bundle.getVersionId())) {
      throw new IllegalStateException(
          "Published release bundle did not persist its exact owner scope");
    }
    GameTenantIdentity identity =
        gameRepository
            .findRuntimeTenantIdentityByTenantKey(version.tenantId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Canonical Game Design tenant provenance is unavailable"));
    validateTenantIdentity(identity, identity.canonicalTenantId(), version.tenantId());
    String releaseBundleIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            identity.canonicalTenantId(),
            version.id(),
            bundle.getPublishWorkflowId(),
            bundle.getManifestHash(),
            objectMapper);

    for (RealmEntryPolicySource source : sources) {
      PublishedRealmEntryPolicyEvidence evidence =
          PublishedRealmEntryPolicyEvidence.create(
              UUID.randomUUID(),
              identity.canonicalTenantId(),
              identity.provenanceKind().name(),
              identity.sourceGameId(),
              identity.sourceLegacyTenantId(),
              version.id(),
              version.versionNumber(),
              source.revision().getId(),
              releaseBundleIdentity,
              bundle.getPublishWorkflowId(),
              bundle.getManifestHash(),
              source.policy(),
              objectMapper);
      RealmEntryPolicy policy = evidence.policy();
      realmEntryPolicyRepository.insert(
          new PublishedRealmEntryPolicy(
              evidence.policyId(),
              evidence.canonicalTenantId(),
              evidence.tenantIdentityProvenanceKind(),
              evidence.sourceGameRowId(),
              evidence.sourceGameTenantKey(),
              evidence.versionId(),
              evidence.versionNumber(),
              bundle.getId(),
              evidence.sourceRevisionId(),
              evidence.releaseBundleIdentity(),
              evidence.publishWorkflowId(),
              evidence.manifestHash(),
              policy.worldSlug(),
              policy.worldDisplayName(),
              policy.realmSlug(),
              policy.realmDisplayName(),
              policy.visible(),
              policy.publicProduction(),
              policy.stateScope().name(),
              policy.entryPolicy().name(),
              policy.canonicalJson(),
              evidence.policyDigest()));
    }
  }

  private void validateTenantIdentity(
      GameTenantIdentity identity, UUID expectedCanonicalTenantId, String expectedTenantKey) {
    if (identity == null
        || identity.canonicalTenantId() == null
        || !identity.canonicalTenantId().equals(expectedCanonicalTenantId)
        || identity.provenanceKind() == null
        || identity.sourceGameId() == null
        || identity.sourceGameId() <= 0
        || identity.sourceLegacyTenantId() == null
        || identity.sourceLegacyTenantId().isBlank()
        || !Objects.equals(identity.sourceLegacyTenantId(), expectedTenantKey)) {
      throw new IllegalStateException(
          "Game Design tenant identity provenance is incomplete or inconsistent");
    }
  }

  private boolean matchesStoredPolicyFields(
      PublishedRealmEntryPolicy stored, RealmEntryPolicy policy) {
    return Objects.equals(stored.worldSlug(), policy.worldSlug())
        && Objects.equals(stored.worldDisplayName(), policy.worldDisplayName())
        && Objects.equals(stored.realmSlug(), policy.realmSlug())
        && Objects.equals(stored.realmDisplayName(), policy.realmDisplayName())
        && stored.visible() == policy.visible()
        && stored.publicProduction() == policy.publicProduction()
        && Objects.equals(stored.stateScope(), policy.stateScope().name())
        && Objects.equals(stored.entryPolicy(), policy.entryPolicy().name());
  }

  private boolean versionIdEquals(long requestedVersionId, Long persistedVersionId) {
    return persistedVersionId != null && requestedVersionId == persistedVersionId;
  }

  private record RealmEntryPolicySource(Revision revision, RealmEntryPolicy policy) {}

  private record PolicyOwnerContext(
      GameTenantIdentity tenantIdentity,
      net.firedevops.firemud.gamedesign.entity.Version version,
      PublishedReleaseBundle bundle,
      String releaseBundleIdentity) {}

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
        deserializeCommandDefinitions(entity.getCommandDefinitionsJson()),
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
