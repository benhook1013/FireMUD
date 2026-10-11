package net.firedevops.firemud.gamedesign.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.dto.PublishedReleaseBundleDto;
import net.firedevops.firemud.gamedesign.dto.VersionAssetArtifactStateDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.service.impl.GameDesignLaunchReadProjectionMapper;
import net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Captures and independently verifies the owner rows behind one run-owned fixture receipt. */
@Service
public class RunOwnedFixtureLaunchReadback {
  private static final String TENANT_ID = "1";
  private static final String TEMPLATE_NAME = "Default Template";
  private static final String MANIFEST_HASH = "demo-manifest-hash";
  private static final String PUBLISH_WORKFLOW_ID = "demo-seed-publish";
  private static final String GENERATION_CONFIG_REVISION = "genrev:demo";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final GameRepository gameRepository;
  private final GameTemplateRepository templateRepository;
  private final VersionRepository versionRepository;
  private final VersionAssetArtifactRepository artifactRepository;
  private final GameAuthoredWorldSourceRepository sourceRepository;
  private final VersionService versionService;
  private final VersionAssetArtifactService artifactService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected owner repositories and services are framework-managed collaborators, not mutable data owned by this helper.")
  public RunOwnedFixtureLaunchReadback(
      GameRepository gameRepository,
      GameTemplateRepository templateRepository,
      VersionRepository versionRepository,
      VersionAssetArtifactRepository artifactRepository,
      GameAuthoredWorldSourceRepository sourceRepository,
      VersionService versionService,
      VersionAssetArtifactService artifactService) {
    this.gameRepository = gameRepository;
    this.templateRepository = templateRepository;
    this.versionRepository = versionRepository;
    this.artifactRepository = artifactRepository;
    this.sourceRepository = sourceRepository;
    this.versionService = versionService;
    this.artifactService = artifactService;
  }

  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Snapshot captureCommittedTransaction(
      long gameRowId,
      long gameTemplateId,
      long versionId,
      AuthoredWorldSourceEvidence registeredSource) {
    return snapshot(gameRowId, gameTemplateId, versionId, registeredSource);
  }

  /**
   * Must be called through this Spring proxy after commit; sourceRepository.read is also proxied.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Snapshot readCommittedOutcome(Snapshot captured) {
    AuthoredWorldSourceEvidence registered = captured.sourceEvidence();
    AuthoredWorldSourceEvidence committed =
        sourceRepository
            .read(
                registered.operationId(),
                registered.canonicalTenantId(),
                registered.worldSlug(),
                registered.targetNamespace())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Committed authored-world source registration cannot be read back"));
    if (!registered.equals(committed)) {
      throw new IllegalStateException(
          "Committed authored-world source readback differs from the registration result");
    }
    Snapshot readback =
        snapshot(
            captured.game().id(), captured.template().id(), captured.version().id(), committed);
    if (!captured.equals(readback)) {
      throw new IllegalStateException(
          "Committed launch owner readback differs from the captured fixture snapshot");
    }
    return readback;
  }

  private Snapshot snapshot(
      long expectedGameRowId,
      long expectedTemplateId,
      long expectedVersionId,
      AuthoredWorldSourceEvidence source) {
    if (source == null
        || expectedGameRowId <= 0L
        || expectedTemplateId <= 0L
        || expectedVersionId <= 0L) {
      throw new IllegalStateException("Run-owned launch snapshot has an invalid owner identity");
    }
    Game game = gameRepository.findByTenantId(source.sourceGameTenantKey());
    if (game == null) {
      throw new IllegalStateException("Run-owned source game row cannot be read");
    }
    GameTemplate template =
        templateRepository
            .findByTenantIdAndId(TENANT_ID, expectedTemplateId)
            .orElseThrow(
                () -> new IllegalStateException("Run-owned default template cannot be read"));
    Version version =
        versionRepository
            .findByTenantIdAndId(TENANT_ID, expectedVersionId)
            .orElseThrow(
                () -> new IllegalStateException("Run-owned published version cannot be read"));
    VersionAssetArtifact artifact =
        artifactRepository
            .findByTenantIdAndVersionId(TENANT_ID, expectedVersionId)
            .orElseThrow(
                () -> new IllegalStateException("Run-owned version artifact cannot be read"));
    PublishedReleaseBundleDto bundle =
        versionService.getPublishedReleaseBundle(TENANT_ID, expectedVersionId);
    VersionAssetArtifactStateDto artifactState =
        artifactService.getState(TENANT_ID, expectedVersionId);
    VersionStateDto versionState = versionService.getVersionState(TENANT_ID, expectedVersionId);

    GameTenantIdentity gameIdentity =
        gameRepository
            .findRuntimeTenantIdentityByTenantKey(game.getTenantId())
            .orElseThrow(
                () -> new IllegalStateException("Run-owned game tenant identity cannot be read"));
    requireOwnerBindings(
        expectedGameRowId,
        expectedTemplateId,
        expectedVersionId,
        source,
        game,
        gameIdentity,
        template,
        version,
        artifact,
        bundle,
        artifactState,
        versionState);

    PublishedReleaseBundle bundleProto = GameDesignLaunchReadProjectionMapper.toProto(bundle);
    VersionAssetArtifactState artifactProto =
        GameDesignLaunchReadProjectionMapper.toProto(artifactState);
    VersionStateSnapshot versionProto = GameDesignLaunchReadProjectionMapper.toProto(versionState);
    return new Snapshot(
        GameSnapshot.from(game),
        TemplateSnapshot.from(template),
        VersionSnapshot.from(version),
        ArtifactSnapshot.from(artifact),
        source,
        bundle,
        artifactState,
        versionState,
        bundleProto,
        artifactProto,
        versionProto);
  }

  private void requireOwnerBindings(
      long expectedGameRowId,
      long expectedTemplateId,
      long expectedVersionId,
      AuthoredWorldSourceEvidence source,
      Game game,
      GameTenantIdentity gameIdentity,
      GameTemplate template,
      Version version,
      VersionAssetArtifact artifact,
      PublishedReleaseBundleDto bundle,
      VersionAssetArtifactStateDto artifactState,
      VersionStateDto versionState) {
    if (artifactState == null || versionState == null) {
      throw new IllegalStateException("Run-owned launch state projections cannot be read");
    }
    if (source.schemaVersion() != 1
        || !"dev".equals(source.targetNamespace())
        || !"demo".equals(source.tenantSlug())
        || !"demo".equals(source.worldSlug())
        || !"Demo World".equals(source.worldDisplayName())
        || !Objects.equals(game.getId(), expectedGameRowId)
        || !Objects.equals(game.getTenantId(), source.sourceGameTenantKey())
        || !Objects.equals(game.getCanonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(gameIdentity.canonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(gameIdentity.sourceGameId(), expectedGameRowId)
        || !Objects.equals(gameIdentity.sourceLegacyTenantId(), source.sourceGameTenantKey())
        || gameIdentity.provenanceKind() == null
        || !gameIdentity.provenanceKind().name().equals(source.provenanceKind())
        || !"Demo Game".equals(game.getName())
        || !"Seed game".equals(game.getDescription())) {
      throw new IllegalStateException("Run-owned source does not bind to the persisted game row");
    }
    if (!Objects.equals(template.getId(), expectedTemplateId)
        || !TENANT_ID.equals(template.getTenantId())
        || !TEMPLATE_NAME.equals(template.getName())
        || !"Demo template".equals(template.getDescription())
        || !"{}".equals(template.getConfig())
        || !Objects.equals(template.getDefaultVersionId(), expectedVersionId)
        || template.getDefaultScriptPatchVersion() != null
        || !"{}".equals(template.getDefaultRuntimeFlagsJson())
        || template.getTemplateReferencePhase() != TemplateReferencePhase.ENFORCED
        || template.getCreatedAt() == null) {
      throw new IllegalStateException(
          "Run-owned default template does not match its seed owner row");
    }
    if (!Objects.equals(version.getId(), expectedVersionId)
        || !TENANT_ID.equals(version.getTenantId())
        || version.getVersionNumber() != 1
        || version.getVersionState() != VersionLifecycleState.PUBLISHED
        || !Objects.equals(version.getVersionStateEpoch(), 1L)
        || version.getScriptPatchVersion() != null
        || version.getBaseVersionId() != null
        || version.isScriptOnly()
        || !"Seed published version".equals(version.getNotes())
        || version.getCreatedAt() == null
        || version.getUpdatedAt() == null
        || version.getCanonicalTenantId() == null
        || version.getCanonicalTenantId().equals(NIL_UUID)
        || !version.getCanonicalTenantId().equals(source.canonicalTenantId())
        || version.getCanonicalVersionId() == null
        || version.getCanonicalVersionId().equals(NIL_UUID)
        || !Objects.equals(version.getIdentitySourceGameRowId(), expectedGameRowId)
        || !Objects.equals(version.getIdentitySourceGameTenantKey(), source.sourceGameTenantKey())
        || !Objects.equals(version.getIdentitySourceProvenanceKind(), source.provenanceKind())) {
      throw new IllegalStateException("Run-owned published version does not match its game source");
    }
    if (artifact.getId() == null
        || artifact.getId() <= 0L
        || !TENANT_ID.equals(artifact.getTenantId())
        || !Objects.equals(artifact.getVersionId(), expectedVersionId)
        || artifact.getExportedVersionNumber() != version.getVersionNumber()
        || artifact.getArtifactState()
            != net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState.PUBLISHED
        || artifact.getStateEpoch() != 1L
        || !Objects.equals(artifact.getManifestHash(), MANIFEST_HASH)
        || !PUBLISH_WORKFLOW_ID.equals(artifact.getLastWorkflowId())
        || artifact.getLastErrorCode() != null
        || artifact.getLastErrorMessage() != null
        || !"[]".equals(artifact.getExportedManifestAssetKeysJson())
        || artifact.getUpdatedAt() == null
        || !TENANT_ID.equals(artifactState.tenantId())
        || !Objects.equals(artifactState.versionId(), expectedVersionId)
        || artifactState.exportedVersionNumber() != artifact.getExportedVersionNumber()
        || !artifact.getArtifactState().name().equals(artifactState.artifactState())
        || artifact.getStateEpoch() != artifactState.stateEpoch()
        || !Objects.equals(artifact.getManifestHash(), artifactState.manifestHash())
        || !Objects.equals(artifact.getLastWorkflowId(), artifactState.lastWorkflowId())
        || !Objects.equals(artifact.getLastErrorCode(), artifactState.lastErrorCode())
        || !Objects.equals(artifact.getLastErrorMessage(), artifactState.lastErrorMessage())
        || !Objects.equals(artifact.getUpdatedAt(), artifactState.updatedAt())
        || !artifactState.exportedManifestAssetKeys().isEmpty()) {
      throw new IllegalStateException("Run-owned artifact state does not match its persisted row");
    }
    if (bundle == null
        || bundle.id() == null
        || bundle.id() <= 0L
        || !TENANT_ID.equals(bundle.tenantId())
        || !Objects.equals(bundle.versionId(), expectedVersionId)
        || bundle.versionNumber() != version.getVersionNumber()
        || !"v1".equals(bundle.attestationSchemaVersion())
        || !PUBLISH_WORKFLOW_ID.equals(bundle.publishWorkflowId())
        || !MANIFEST_HASH.equals(bundle.manifestHash())
        || bundle.publishedAt() == null
        || !GENERATION_CONFIG_REVISION.equals(bundle.generationConfigRevision())
        || bundle.scriptOnly()
        || bundle.scriptPatchVersion() != null
        || !Objects.equals(bundle.canonicalTenantId(), source.canonicalTenantId())
        || !Objects.equals(bundle.canonicalTenantId(), version.getCanonicalTenantId())
        || !Objects.equals(bundle.canonicalVersionId(), version.getCanonicalVersionId())
        || bundle.publishedReleaseBundleRef() == null
        || bundle.publishedReleaseBundleRef().isBlank()
        || bundle.manifestSchemaVersion() != null
        || bundle.artifactDigests() != null
        || !bundle.requiredManifestAssetKeys().isEmpty()
        || !bundle.participantDigests().isEmpty()
        || !bundle.commandDefinitions().isEmpty()) {
      throw new IllegalStateException(
          "Run-owned release bundle does not match its persisted version");
    }
    if (!TENANT_ID.equals(versionState.tenantId())
        || versionState.versionId() != expectedVersionId
        || versionState.versionState() != version.getVersionState()
        || versionState.versionStateEpoch() != version.getVersionStateEpoch()
        || !Objects.equals(versionState.updatedAt(), version.getUpdatedAt())) {
      throw new IllegalStateException("Run-owned version state does not match its persisted row");
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Generated protobuf messages are immutable values and do not expose mutable state through this snapshot.")
  public record Snapshot(
      GameSnapshot game,
      TemplateSnapshot template,
      VersionSnapshot version,
      ArtifactSnapshot artifact,
      AuthoredWorldSourceEvidence sourceEvidence,
      PublishedReleaseBundleDto bundle,
      VersionAssetArtifactStateDto artifactState,
      VersionStateDto versionState,
      PublishedReleaseBundle bundleProto,
      VersionAssetArtifactState artifactProto,
      VersionStateSnapshot versionProto) {
    public long gameTemplateId() {
      return template.id();
    }
  }

  public record GameSnapshot(
      Long id, String tenantId, UUID canonicalTenantId, String name, String description) {
    private static GameSnapshot from(Game game) {
      return new GameSnapshot(
          game.getId(),
          game.getTenantId(),
          game.getCanonicalTenantId(),
          game.getName(),
          game.getDescription());
    }
  }

  public record TemplateSnapshot(
      Long id,
      String tenantId,
      String name,
      String description,
      String config,
      Long defaultVersionId,
      String defaultScriptPatchVersion,
      String defaultRuntimeFlagsJson,
      TemplateReferencePhase templateReferencePhase,
      LocalDateTime createdAt) {
    private static TemplateSnapshot from(GameTemplate template) {
      return new TemplateSnapshot(
          template.getId(),
          template.getTenantId(),
          template.getName(),
          template.getDescription(),
          template.getConfig(),
          template.getDefaultVersionId(),
          template.getDefaultScriptPatchVersion(),
          template.getDefaultRuntimeFlagsJson(),
          template.getTemplateReferencePhase(),
          template.getCreatedAt());
    }
  }

  public record VersionSnapshot(
      Long id,
      String tenantId,
      UUID canonicalVersionId,
      UUID canonicalTenantId,
      Long identitySourceGameRowId,
      String identitySourceGameTenantKey,
      String identitySourceProvenanceKind,
      int versionNumber,
      VersionLifecycleState versionState,
      Long versionStateEpoch,
      String scriptPatchVersion,
      Long baseVersionId,
      boolean scriptOnly,
      String notes,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    private static VersionSnapshot from(Version version) {
      return new VersionSnapshot(
          version.getId(),
          version.getTenantId(),
          version.getCanonicalVersionId(),
          version.getCanonicalTenantId(),
          version.getIdentitySourceGameRowId(),
          version.getIdentitySourceGameTenantKey(),
          version.getIdentitySourceProvenanceKind(),
          version.getVersionNumber(),
          version.getVersionState(),
          version.getVersionStateEpoch(),
          version.getScriptPatchVersion(),
          version.getBaseVersionId(),
          version.isScriptOnly(),
          version.getNotes(),
          version.getCreatedAt(),
          version.getUpdatedAt());
    }
  }

  public record ArtifactSnapshot(
      Long id,
      String tenantId,
      Long versionId,
      int exportedVersionNumber,
      net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState artifactState,
      long stateEpoch,
      String manifestHash,
      String lastWorkflowId,
      String lastErrorCode,
      String lastErrorMessage,
      String exportedManifestAssetKeysJson,
      LocalDateTime updatedAt) {
    private static ArtifactSnapshot from(VersionAssetArtifact artifact) {
      return new ArtifactSnapshot(
          artifact.getId(),
          artifact.getTenantId(),
          artifact.getVersionId(),
          artifact.getExportedVersionNumber(),
          artifact.getArtifactState(),
          artifact.getStateEpoch(),
          artifact.getManifestHash(),
          artifact.getLastWorkflowId(),
          artifact.getLastErrorCode(),
          artifact.getLastErrorMessage(),
          artifact.getExportedManifestAssetKeysJson(),
          artifact.getUpdatedAt());
    }
  }
}
