package net.firedevops.firemud.gamedesign.data;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
    prefix = "firemud.smoke.seed-demo-runtime",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
public class TestDataSeeder implements ApplicationRunner {
  private final GameRepository gameRepository;
  private final GameTemplateRepository templateRepository;
  private final RevisionRepository revisionRepository;
  private final VersionRepository versionRepository;
  private final PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  private final VersionAssetArtifactRepository versionAssetArtifactRepository;

  private static final String DEMO_TENANT_ID = "1";
  private static final String DEMO_MANIFEST_HASH = "demo-manifest-hash";
  private static final String DEMO_GENERATION_CONFIG_REVISION = "genrev:demo";
  private static final String DEMO_PUBLISH_WORKFLOW_ID = "demo-seed-publish";
  private static final String DEMO_REVISION_KIND = "GENERIC";
  private static final int DEMO_VERSION_NUMBER = 1;
  private static final String DEMO_TEMPLATE_NAME = "Default Template";

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Game game = gameRepository.findByTenantId(DEMO_TENANT_ID);
    if (game == null) {
      game = new Game();
    }
    game.setTenantId(DEMO_TENANT_ID);
    game.setName("Demo Game");
    game.setDescription("Seed game");
    gameRepository.save(game);

    Version version =
        versionRepository
            .findByTenantIdAndVersionNumber(DEMO_TENANT_ID, DEMO_VERSION_NUMBER)
            .orElse(null);
    if (version == null) {
      version = new Version();
      version.setTenantId(DEMO_TENANT_ID);
      version.setVersionNumber(DEMO_VERSION_NUMBER);
    }
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(1L);
    version.setScriptPatchVersion(null);
    version.setBaseVersionId(null);
    version.setScriptOnly(false);
    version.setNotes("Seed published version");
    version = versionRepository.save(version);
    Long versionId = version.getId();

    GameTemplate template =
        templateRepository.findByTenantIdAndName(DEMO_TENANT_ID, DEMO_TEMPLATE_NAME).orElse(null);
    if (template == null) {
      template = new GameTemplate();
      template.setTenantId(DEMO_TENANT_ID);
      template.setName(DEMO_TEMPLATE_NAME);
    }
    template.setDescription("Demo template");
    template.setConfig("{}");
    template.setDefaultVersionId(version.getId());
    template.setDefaultScriptPatchVersion(null);
    template.setDefaultRuntimeFlagsJson("{}");
    templateRepository.save(template);

    Revision revision =
        revisionRepository
            .findByTenantIdAndVersionIdAndRevisionKind(
                DEMO_TENANT_ID, versionId, DEMO_REVISION_KIND)
            .orElse(null);
    if (revision == null) {
      revision = new Revision();
      revision.setTenantId(DEMO_TENANT_ID);
      revision.setVersionId(versionId);
      revision.setRevisionKind(DEMO_REVISION_KIND);
    }
    revision.setAuthorAccountId(1L);
    revision.setData("{}");
    revisionRepository.save(revision);

    PublishedReleaseBundle bundle =
        publishedReleaseBundleRepository
            .findByTenantIdAndVersionId(DEMO_TENANT_ID, versionId)
            .orElseGet(PublishedReleaseBundle::new);
    bundle.setTenantId(DEMO_TENANT_ID);
    bundle.setVersionId(versionId);
    bundle.setVersionNumber(version.getVersionNumber());
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
    bundle.setManifestHash(DEMO_MANIFEST_HASH);
    bundle.setGenerationConfigRevision(DEMO_GENERATION_CONFIG_REVISION);
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setScriptOnly(false);
    bundle.setScriptPatchVersion(null);
    bundle = publishedReleaseBundleRepository.save(bundle);

    VersionAssetArtifact artifact =
        versionAssetArtifactRepository
            .findByTenantIdAndVersionId(DEMO_TENANT_ID, versionId)
            .orElseGet(VersionAssetArtifact::new);
    artifact.setTenantId(DEMO_TENANT_ID);
    artifact.setVersionId(versionId);
    artifact.setExportedVersionNumber(version.getVersionNumber());
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(Math.max(artifact.getStateEpoch(), 1L));
    artifact.setManifestHash(bundle.getManifestHash());
    artifact.setLastWorkflowId(DEMO_PUBLISH_WORKFLOW_ID);
    artifact.setLastErrorCode(null);
    artifact.setLastErrorMessage(null);
    artifact.setExportedManifestAssetKeysJson("[]");
    versionAssetArtifactRepository.save(artifact);
  }
}
