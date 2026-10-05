package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class VersionAssetArtifactRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private VersionAssetArtifactRepository repository;
  @Autowired private VersionAssetArtifactService artifactService;
  @Autowired private GameRepository gameRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void stagedIntentCommitsThroughSpringProxyBeforeCallingTransactionRollsBack() {
    TransactionTemplate owner = new TransactionTemplate(transactionManager);
    Game game = new Game();
    game.setTenantId(UUID.randomUUID().toString());
    game.setName("staged intent proof");
    game.setDescription("actual owner source");
    Game storedGame = owner.execute(status -> gameRepository.save(game));
    assertThat(storedGame).isNotNull();
    Version version = new Version();
    version.setTenantId(storedGame.getTenantId());
    version.setVersionNumber(1);
    version.setNotes("staged intent proof");
    Version storedVersion = owner.execute(status -> versionRepository.save(version));
    assertThat(storedVersion).isNotNull();
    String workflow = "stable-staged-proof-" + UUID.randomUUID();
    var committed =
        owner.execute(
            status -> {
              var staged =
                  artifactService.stageExport(
                      storedGame.getTenantId(), storedVersion.getId(), 1, workflow);
              status.setRollbackOnly();
              return staged;
            });
    assertThat(committed).isNotNull();
    assertThat(committed.artifactState()).isEqualTo("STAGED");
    assertThat(committed.stateEpoch()).isEqualTo(1L);
    assertThat(artifactService.getState(storedGame.getTenantId(), storedVersion.getId()))
        .isEqualTo(committed);
    assertThat(
            artifactService.stageExport(
                storedGame.getTenantId(), storedVersion.getId(), 1, workflow))
        .isEqualTo(committed);
    assertThat(
            repository
                .findByTenantIdAndVersionId(storedGame.getTenantId(), storedVersion.getId())
                .orElseThrow()
                .getManifestSchemaVersion())
        .isNull();
  }

  @Test
  void saveAndFindRoundTripTimestampFields() {
    VersionAssetArtifact artifact = new VersionAssetArtifact();
    artifact.setTenantId("1");
    artifact.setVersionId(7L);
    artifact.setExportedVersionNumber(1);
    artifact.setArtifactState(VersionAssetArtifactState.PUBLISHED);
    artifact.setStateEpoch(1L);
    artifact.setManifestHash("demo-manifest-hash");
    artifact.setLastWorkflowId("demo-seed");
    artifact.setExportedManifestAssetKeysJson("[]");

    VersionAssetArtifact inserted = repository.save(artifact);

    assertThat(inserted.getId()).isNotNull();
    assertThat(inserted.getTenantId()).isEqualTo("1");
    assertThat(inserted.getVersionId()).isEqualTo(7L);
    assertThat(inserted.getExportedVersionNumber()).isEqualTo(1);
    assertThat(inserted.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);

    VersionAssetArtifact reloaded = repository.findByTenantIdAndVersionId("1", 7L).orElseThrow();

    assertThat(reloaded.getId()).isEqualTo(inserted.getId());
    assertThat(reloaded.getUpdatedAt()).isNotNull();
    assertThat(reloaded.getManifestHash()).isEqualTo("demo-manifest-hash");
    assertThat(reloaded.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);

    LocalDateTime updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0);
    reloaded.setUpdatedAt(updatedAt);
    reloaded.setManifestHash("demo-manifest-hash-2");
    reloaded.setStateEpoch(Math.addExact(reloaded.getStateEpoch(), 1L));
    repository.save(reloaded);

    VersionAssetArtifact updated = repository.findByTenantIdAndVersionId("1", 7L).orElseThrow();
    assertThat(updated.getUpdatedAt()).isEqualTo(updatedAt);
    assertThat(updated.getManifestHash()).isEqualTo("demo-manifest-hash-2");
  }
}
