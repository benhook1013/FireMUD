package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.VersionAssetArtifact;
import net.firedevops.firemud.gamedesign.model.VersionAssetArtifactState;
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

    repository.save(artifact);

    VersionAssetArtifact reloaded = repository.findByTenantIdAndVersionId("1", 7L).orElseThrow();

    assertThat(reloaded.getUpdatedAt()).isNotNull();
    assertThat(reloaded.getManifestHash()).isEqualTo("demo-manifest-hash");
    assertThat(reloaded.getArtifactState()).isEqualTo(VersionAssetArtifactState.PUBLISHED);
  }
}
