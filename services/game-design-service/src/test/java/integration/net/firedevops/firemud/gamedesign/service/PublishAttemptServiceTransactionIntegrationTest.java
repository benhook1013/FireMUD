package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.PublishAttempt;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.PublishAttemptStatus;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof that final full-version publication writes share one rollback boundary. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class PublishAttemptServiceTransactionIntegrationTest {
  private static final String TENANT_ID = "9001";
  private static final String WORKFLOW_ID = "full-version-transaction-integration-test";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private GameRepository gameRepository;
  @Autowired private PublishAttemptService publishAttemptService;
  @Autowired private PublishAttemptRepository publishAttemptRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private VersionAssetArtifactRepository versionAssetArtifactRepository;
  @Autowired private VersionRepository versionRepository;

  @Test
  void fullVersionTransactionRollsBackVersionBundleArtifactAndAttemptTogether() {
    Game game = new Game();
    game.setTenantId(TENANT_ID);
    game.setName("transaction-proof-game");
    gameRepository.save(game);

    AtomicReference<Version> savedVersion = new AtomicReference<>();
    assertThatThrownBy(
            () ->
                publishAttemptService.executeFullVersionTransaction(
                    () -> {
                      Version version = new Version();
                      version.setTenantId(TENANT_ID);
                      version.setVersionNumber(1);
                      version.setVersionState(VersionLifecycleState.PUBLISHED);
                      version.setVersionStateEpoch(2L);
                      version.setNotes("transaction proof");
                      Version persistedVersion = versionRepository.save(version);
                      savedVersion.set(persistedVersion);

                      PublishAttempt attempt = new PublishAttempt();
                      attempt.setTenantId(TENANT_ID);
                      attempt.setPublishWorkflowId(WORKFLOW_ID);
                      attempt.setPublishType(PublishType.FULL_VERSION);
                      attempt.setStatus(PublishAttemptStatus.SUCCEEDED);
                      attempt.setVersionId(persistedVersion.getId());
                      attempt.setVersionNumber(persistedVersion.getVersionNumber());
                      publishAttemptRepository.save(attempt);

                      PublishedReleaseBundle bundle = new PublishedReleaseBundle();
                      bundle.setTenantId(TENANT_ID);
                      bundle.setVersionId(persistedVersion.getId());
                      bundle.setVersionNumber(persistedVersion.getVersionNumber());
                      bundle.setAttestationSchemaVersion("v1");
                      bundle.setPublishWorkflowId(WORKFLOW_ID);
                      bundle.setManifestHash("transaction-proof-manifest");
                      bundle.setGenerationConfigRevision("transaction-proof-generation");
                      bundle.setRequiredManifestAssetKeysJson("[]");
                      bundle.setParticipantDigestsJson("[]");
                      bundle.setCommandDefinitionsJson("[]");
                      publishedReleaseBundleRepository.save(bundle);

                      VersionAssetArtifact artifact = new VersionAssetArtifact();
                      artifact.setTenantId(TENANT_ID);
                      artifact.setVersionId(persistedVersion.getId());
                      artifact.setExportedVersionNumber(persistedVersion.getVersionNumber());
                      artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
                      artifact.setStateEpoch(2L);
                      artifact.setManifestHash(bundle.getManifestHash());
                      artifact.setLastWorkflowId(WORKFLOW_ID);
                      artifact.setExportedManifestAssetKeysJson("[]");
                      versionAssetArtifactRepository.save(artifact);

                      throw new IllegalStateException("forced finalization failure");
                    }))
        .isInstanceOf(PublishAttemptService.FullVersionTransactionException.class)
        .hasCauseInstanceOf(IllegalStateException.class);

    long versionId = savedVersion.get().getId();
    assertThat(versionRepository.findByTenantIdAndId(TENANT_ID, versionId)).isEmpty();
    assertThat(publishAttemptRepository.findByPublishWorkflowId(WORKFLOW_ID)).isEmpty();
    assertThat(publishedReleaseBundleRepository.findByTenantIdAndVersionId(TENANT_ID, versionId))
        .isEmpty();
    assertThat(versionAssetArtifactRepository.findByTenantIdAndVersionId(TENANT_ID, versionId))
        .isEmpty();
    assertThat(gameRepository.findByTenantId(TENANT_ID)).isNotNull();
  }
}
