package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
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
class LaunchDescriptorServiceIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", postgres::getDatabaseName);
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
  }

  @Autowired private LaunchDescriptorService launchDescriptorService;
  @Autowired private GameTemplateRepository gameTemplateRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;

  @Test
  void resolvesLaunchDescriptorForJsonConfiguredTemplate() {
    Version version = new Version();
    version.setTenantId("1");
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setVersionStateEpoch(1L);
    version.setNotes("integration test version");
    version.setUpdatedAt(LocalDateTime.now());
    version = versionRepository.save(version);

    GameTemplate template = new GameTemplate();
    template.setTenantId("1");
    template.setName("Integration Template");
    template.setDescription("launch descriptor integration template");
    template.setConfig("{}");
    template.setDefaultVersionId(version.getId());
    template.setDefaultRuntimeFlagsJson("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    template = gameTemplateRepository.save(template);

    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId("1");
    bundle.setVersionId(version.getId());
    bundle.setVersionNumber(1);
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("integration-seed");
    bundle.setManifestHash("integration-manifest");
    bundle.setGenerationConfigRevision("genrev:integration");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setScriptOnly(false);
    publishedReleaseBundleRepository.save(bundle);

    var descriptor =
        launchDescriptorService.resolveLaunchDescriptor(
            "1", template.getId(), "integration-cp-1", null, null, null, null);

    assertThat(descriptor.gameTemplateId()).isEqualTo(template.getId());
    assertThat(descriptor.versionId()).isEqualTo(version.getId());
    assertThat(descriptor.generationConfigRevision()).isEqualTo("genrev:integration");
    assertThat(descriptor.versionStateEpoch()).isEqualTo(1L);
  }
}
