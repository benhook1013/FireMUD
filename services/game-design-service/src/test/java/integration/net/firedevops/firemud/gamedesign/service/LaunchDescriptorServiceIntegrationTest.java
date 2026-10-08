package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
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
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private LaunchDescriptorService launchDescriptorService;
  @Autowired private LaunchDescriptorRepository launchDescriptorRepository;

  @Test
  void legacyNumericResolveFailsClosedWithoutPersistingDescriptor() {
    assertThatThrownBy(
            () ->
                launchDescriptorService.resolveLaunchDescriptor(
                    "1", 9L, "integration-cp-unbound", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
                + " required to resolve a launch descriptor");

    assertThat(launchDescriptorRepository.findByPrivateRequest("1", "integration-cp-unbound"))
        .isEmpty();
  }
}
