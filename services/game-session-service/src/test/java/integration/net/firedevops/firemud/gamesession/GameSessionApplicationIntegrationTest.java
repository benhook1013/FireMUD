package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.test.FiremudAuthTestProperties;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameSessionServiceApplication.class,
    properties = {
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0",
      FiremudAuthTestProperties.JWT_SECRET,
      FiremudAuthTestProperties.JWT_EXPIRATION,
      FiremudAuthTestProperties.HTTP_ENABLED,
      FiremudAuthTestProperties.HTTP_ROLE_REQUIREMENT_PRIVILEGED,
      "firemud.auth.http.public-routes[0].method=GET",
      "firemud.auth.http.public-routes[0].path-pattern=/ping"
    })
class GameSessionApplicationIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final JwtUtil JWT_UTIL =
      new JwtUtil("testsecretkeytestsecretkeytest1234", 3600000L);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_session_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @LocalServerPort private int port;

  @MockitoBean private GameLogicClient gameLogicClient;
  @MockitoBean private WorldManagementClient worldManagementClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private SharedSettingsAuthorityReader sharedSettingsAuthorityReader;

  @Autowired private SessionContextService sessionContextService;

  @Autowired private GameInstanceRepository gameInstanceRepository;

  @Test
  void legacyNumericStartIsDeniedBeforePersistenceOrWorldPreparation() throws Exception {
    when(gameDesignClient.resolveLaunchDescriptor(42L, 7L, "cp-1"))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("FAILED_PRECONDITION")
                        .setMessage("Numeric Game Session launch selectors are not authorizing")
                        .build())
                .build());
    StartSessionRequest request = new StartSessionRequest(42L, 7L, "cp-1", 100L);

    HttpRequest.Builder httpRequest =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions"))
            .header("Content-Type", "application/json");
    privilegedHeaders().forEach(httpRequest::header);
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                httpRequest
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            OBJECT_MAPPER.writeValueAsString(request), StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
    assertThat(gameInstanceRepository.findAll()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(worldManagementClient);
  }

  @Test
  void infoEndpointExposesRuntimeIdentity() throws Exception {
    String body =
        HttpTestSupport.getBody(
            "http://localhost:" + port + "/actuator/runtime", privilegedHeaders());

    assertThat(body).contains("\"service\":\"game-session-service\"");
    assertThat(body).contains("\"serviceInstanceId\"");
    assertThat(body).contains("\"bootedAt\"");
  }

  @Test
  void effectiveSettingsEndpointMergesScopedOverridesForPersistedSession() {
    when(sharedSettingsAuthorityReader.readOverrides(42L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    new ScopedSettingsOverrides.ReconnectionOverride(
                        new ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride(
                            240_000L, null),
                        null),
                    new ScopedSettingsOverrides.CommunicationOverride(640, false),
                    new ScopedSettingsOverrides.PresentationOverride(null, null, true, null),
                    null,
                    new ScopedSettingsOverrides.WorldTopologyOverride(
                        ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel
                            .REGION_AREA_AND_MAP,
                        null),
                    null,
                    new ScopedSettingsOverrides.CommandCapabilitiesOverride(
                        false, null, null, true)),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    new ScopedSettingsOverrides.PresentationOverride(
                        null,
                        ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC,
                        null,
                        null),
                    new ScopedSettingsOverrides.MovementOverride(false),
                    new ScopedSettingsOverrides.WorldTopologyOverride(null, true))));

    sessionContextService.save(
        new SessionContext(
            999L,
            42L,
            "100",
            "player@example.com",
            55L,
            "Player",
            7L,
            "R-1",
            "jwt-token",
            "en-NZ",
            7L));

    String body =
        HttpTestSupport.getBodyUnchecked(
            "http://localhost:" + port + "/actuator/settings/effective?sessionId=999",
            privilegedHeaders());

    assertThat(body).contains("\"persistedSession\":true");
    assertThat(body).contains("\"sessionId\":999");
    assertThat(body).contains("\"tenantId\":42");
    assertThat(body).contains("\"gameInstanceId\":0");
    assertThat(body).contains("\"briefEnabledByDefault\":true");
    assertThat(body).contains("\"defaultColorMode\":\"BASIC\"");
    assertThat(body).contains("\"prompt\":");
    assertThat(body).contains("\"enabled\":true");
    assertThat(body).contains("\"transcriptRendering\":");
    assertThat(body).contains("\"reconnectionPolicy\":");
    assertThat(body).contains("\"reconnectBuffer\":");
    assertThat(body).contains("\"postMoveLookEnabled\":false");
    assertThat(body).contains("\"movementPostMoveView\":");
    assertThat(body).contains("\"scopeModel\":\"REGION_AREA_AND_MAP\"");
    assertThat(body).contains("\"worldTopologyScopeModel\":");
    assertThat(body).contains("\"mapEnabled\":true");
    assertThat(body).contains("\"areasEnabled\":true");
    assertThat(body).contains("\"regionsEnabled\":true");
    assertThat(body).contains("\"worldTopologyRegionBehavior\":");
    assertThat(body).contains("\"communicationOverrides\":");
    assertThat(body).contains("\"maxMessageLength\":640");
    assertThat(body).contains("\"whisperObserverMetadataEnabled\":false");
    assertThat(body).contains("\"commandCapabilities\":");
    assertThat(body).contains("\"socialEnabled\":false");
    assertThat(body).contains("\"commandHistoryEnabled\":true");
    assertThat(body).contains("\"sources\":[\"operatorDefaults\",\"tenantPersistedOverride:42\"");
    assertThat(body)
        .contains("\"sources\":[\"operatorDefaults\",\"gameInstancePersistedOverride:7\"]");
    assertThat(body).contains("\"sources\":[\"tenantPersistedOverride:42\"]");
    assertThat(body).contains("\"resumeWindowMs\":240000");
    assertThat(body).contains("\"minMessages\":8");
  }

  private Map<String, String> privilegedHeaders() {
    return Map.of(
        HttpHeaders.AUTHORIZATION,
        "Bearer "
            + JWT_UTIL.generateToken(
                "game-session-test", Map.of("globalRoles", List.of("platformAdmin"))));
  }
}
