package net.firedevops.firemud.socialgroups;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Cross-service integration test verifying Logging Admin Service starts alongside Social Groups
 * Service.
 */
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = SocialGroupsServiceApplication.class,
    properties = {"server.address=127.0.0.1", "spring.grpc.server.address=127.0.0.1:0"})
@DirtiesContext
class SocialGroupsCrossServiceIntegrationTest {

  private static final String JWT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
  private static final String READINESS_PATH = "/actuator/health/readiness";

  @TempDir static Path tlsDirectory;

  private static Network network;
  private static GenericContainer<?> loggingAdminService;
  private static PostgreSQLContainer<?> postgres;
  private static GenericContainer<?> redis;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void startContainers() throws Exception {
    String imageReference = System.getProperty("loggingAdminFixtureImage");
    String expectedImageId = System.getProperty("loggingAdminFixtureImageId");
    Assumptions.assumeTrue(
        imageReference != null || expectedImageId != null,
        "No explicit Logging & Admin fixture supplied");
    assertThat(imageReference).as("loggingAdminFixtureImage").isNotBlank();
    assertThat(expectedImageId)
        .as("loggingAdminFixtureImageId (full Docker image ID)")
        .matches("sha256:[0-9a-f]{64}");

    DockerImageName image = DockerImageName.parse(imageReference);
    image.assertValid();
    DockerClient docker = DockerClientFactory.instance().client();
    String localImageId;
    try {
      localImageId = docker.inspectImageCmd(imageReference).exec().getId();
    } catch (NotFoundException e) {
      throw new IllegalStateException(
          "Logging & Admin fixture image is not present locally: " + imageReference, e);
    }
    assertThat(localImageId).as("local image ID for %s", imageReference).isEqualTo(expectedImageId);

    network = Network.newNetwork();
    postgres =
        new PostgreSQLContainer<>(TestContainerImages.postgres())
            .withPassword(UUID.randomUUID().toString())
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withCreateContainerCmdModifier(command -> bindLoopback(command, 5432));
    postgres.withImagePullPolicy(ignored -> false);
    postgres.start();
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
    redis =
        new GenericContainer<>(TestContainerImages.redis())
            .withImagePullPolicy(ignored -> false)
            .withNetwork(network)
            .withNetworkAliases("redis")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> bindLoopback(command, 6379));
    redis.start();

    pki =
        LoopbackMtlsTestPki.create(
            tlsDirectory,
            "Co-start test CA",
            workloadIdentity("social-client", "social-groups-service", false),
            workloadIdentity("social-server", "social-groups-service", true),
            workloadIdentity("logging-client", "logging-admin-service", false),
            workloadIdentity("logging-server", "logging-admin-service", true));
    var client = pki.clientProperties(tlsDirectory, pki.identity("logging-client"));
    var server = pki.clientProperties(tlsDirectory, pki.identity("logging-server"));
    loggingAdminService =
        new GenericContainer<>(image)
            .withImagePullPolicy(ignored -> false)
            .withNetwork(network)
            // Use the checked ID even if the local reference is retagged after inspection.
            .withCreateContainerCmdModifier(command -> {
              command.withImage(expectedImageId);
              bindLoopback(command, 8080);
            })
            .withExposedPorts(8080)
            .withEnv("FIREMUD_AUTH_JWT_SECRET", JWT_SECRET)
            .withEnv("FIREMUD_POSTGRES_HOST", "postgres")
            .withEnv("FIREMUD_POSTGRES_DATABASE", postgres.getDatabaseName())
            .withEnv("FIREMUD_POSTGRES_USERNAME", postgres.getUsername())
            .withEnv("FIREMUD_POSTGRES_PASSWORD", postgres.getPassword())
            .withEnv("SERVICE_SCHEMA", "logging_admin_service")
            .withEnv("FIREMUD_REDIS_HOST", "redis")
            // The real image entrypoint waits for both Redis roles before launching Java.
            .withEnv("FIREMUD_REDIS_COORD_HOST", "redis")
            .withEnv("FIREMUD_REDIS_CACHE_HOST", "redis")
            .withEnv("FIREMUD_GRPC_CERT_CHAIN_PATH", "/app/fixture-client.crt")
            .withEnv("FIREMUD_GRPC_PRIVATE_KEY_PATH", "/app/fixture-client.key")
            .withEnv("FIREMUD_GRPC_CA_CERT_PATH", "/app/fixture-ca.crt")
            .withEnv(
                "SPRING_APPLICATION_JSON",
                """
                {
                  "firemud.services.account-service": "127.0.0.1:1",
                  "firemud.services.game-session-service": "127.0.0.1:1",
                  "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate": "file:/app/fixture-server.crt",
                  "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key": "file:/app/fixture-server.key",
                  "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate": "file:/app/fixture-ca.crt"
                }
                """)
            .withCopyFileToContainer(
                MountableFile.forHostPath(client.getCertChain(), 0444), "/app/fixture-client.crt")
            .withCopyFileToContainer(
                MountableFile.forHostPath(client.getPrivateKey(), 0444), "/app/fixture-client.key")
            .withCopyFileToContainer(
                MountableFile.forHostPath(server.getCertChain(), 0444), "/app/fixture-server.crt")
            .withCopyFileToContainer(
                MountableFile.forHostPath(server.getPrivateKey(), 0444), "/app/fixture-server.key")
            .withCopyFileToContainer(
                MountableFile.forHostPath(client.getCaCert(), 0444), "/app/fixture-ca.crt")
            .waitingFor(Wait.forHttp(READINESS_PATH).withStartupTimeout(Duration.ofMinutes(2)));
    loggingAdminService.start();
    assertThat(docker.inspectContainerCmd(loggingAdminService.getContainerId()).exec().getImageId())
        .as("started Logging & Admin container image ID")
        .isEqualTo(expectedImageId);
  }

  private static void bindLoopback(CreateContainerCmd command, int port) {
    var hostConfig = command.getHostConfig();
    if (hostConfig == null) {
      throw new IllegalStateException("The fixture requires Docker host configuration.");
    }
    hostConfig.withPortBindings(
        new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(port)));
  }

  private static IdentitySpec workloadIdentity(String alias, String service, boolean server) {
    return new IdentitySpec(alias, "spiffe://firemud/ns/test/sa/" + service, server);
  }

  @DynamicPropertySource
  static void configureSocial(DynamicPropertyRegistry registry) throws Exception {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "social_groups_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
    registry.add("firemud.auth.jwt-secret", () -> JWT_SECRET);

    // No peer is called by /ping; keep the outbound channels on loopback.
    registry.add("firemud.services.account-service", () -> "127.0.0.1:6565");
    registry.add("firemud.services.game-session-service", () -> "127.0.0.1:6565");
    registry.add("firemud.services.logging-admin-service", () -> "127.0.0.1:6565");

    var client = pki.clientProperties(tlsDirectory, pki.identity("social-client"));
    var server = pki.clientProperties(tlsDirectory, pki.identity("social-server"));
    registry.add("firemud.grpc.cert-chain", client::getCertChain);
    registry.add("firemud.grpc.private-key", client::getPrivateKey);
    registry.add("firemud.grpc.ca-cert", client::getCaCert);
    registry.add(
        "spring.ssl.bundle.pem.firemud-grpc.keystore.certificate",
        () -> Path.of(server.getCertChain()).toUri().toString());
    registry.add(
        "spring.ssl.bundle.pem.firemud-grpc.keystore.private-key",
        () -> Path.of(server.getPrivateKey()).toUri().toString());
    registry.add(
        "spring.ssl.bundle.pem.firemud-grpc.truststore.certificate",
        () -> Path.of(server.getCaCert()).toUri().toString());
  }

  @AfterAll
  static void stopContainers() {
    try (var fixtureNetwork = network;
        var fixturePostgres = postgres;
        var fixtureRedis = redis;
        var fixtureLoggingAdmin = loggingAdminService) {
      // Close in reverse startup order, including after partial startup.
    }
  }

  @LocalServerPort private int port;

  @Test
  void socialGroupsRunsAlongsideLoggingAdminService() throws Exception {
    String socialBaseUrl = "http://127.0.0.1:" + port;
    HttpTestSupport.awaitReadiness(socialBaseUrl + READINESS_PATH, Duration.ofSeconds(10));
    HttpTestSupport.awaitReadiness(
        "http://127.0.0.1:" + loggingAdminService.getMappedPort(8080) + READINESS_PATH,
        Duration.ofSeconds(10));
    assertThat(loggingAdminService.isRunning()).isTrue();

    String accountId = accountUuid(1);
    String token =
        new JwtUtil(JWT_SECRET, 3600000L)
            .generateToken(
                accountId, Map.of("accountId", accountId, "globalRoles", List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(socialBaseUrl + "/ping"))
            .timeout(Duration.ofSeconds(5))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("pong");
    }
  }
}
