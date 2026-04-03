package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ResultStatus;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameSessionServiceApplication.class,
    properties = {
      "game-session.dev-isolated=false",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0"
    })
class GameSessionApplicationIntegrationTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", () -> postgres.getDatabaseName());
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
    registry.add("firemud.redis.host", redis::getHost);
    registry.add("firemud.redis.port", () -> redis.getMappedPort(6379));
  }

  @LocalServerPort private int port;

  @MockitoBean private GameLogicClient gameLogicClient;
  @MockitoBean private WorldManagementClient worldManagementClient;
  @MockitoBean private EntityManagementClient entityManagementClient;
  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private SharedSettingsAuthorityReader sharedSettingsAuthorityReader;

  @org.springframework.beans.factory.annotation.Autowired
  private SessionContextService sessionContextService;

  @Test
  void startSessionUsesRealHttpAndPersistencePath() throws Exception {
    StartSessionRequest request = new StartSessionRequest(42L, "1.0.0", "patch-1", 100L);

    String responseBody =
        HttpTestSupport.postJsonBodyUnchecked(
            "http://localhost:" + port + "/sessions", OBJECT_MAPPER.writeValueAsString(request));
    ApiResponse<GameInstanceDto> body =
        OBJECT_MAPPER.readValue(responseBody, new TypeReference<ApiResponse<GameInstanceDto>>() {});

    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(body.data()).isNotNull();
    assertThat(body.data().tenantId()).isEqualTo(42L);
    assertThat(body.data().runtimeVersion()).isEqualTo("1.0.0");
    assertThat(body.data().scriptPatchVersion()).isEqualTo("patch-1");
    assertThat(body.data().ownerAccountId()).isEqualTo(100L);
    assertThat(body.data().status()).isEqualTo("RUNNING");
    assertThat(body.data().id()).isPositive();
  }

  @Test
  void infoEndpointExposesRuntimeIdentity() throws Exception {
    String body = HttpTestSupport.getBody("http://localhost:" + port + "/actuator/runtime");

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
                    null,
                    new ScopedSettingsOverrides.PresentationOverride(null, null, true, null),
                    null,
                    new ScopedSettingsOverrides.WorldTopologyOverride(
                        ScopedSettingsOverrides.WorldTopologyOverride.ScopeModel
                            .REGION_AREA_AND_MAP,
                        null)),
                new ScopedSettingsOverrides(
                    null,
                    null,
                    new ScopedSettingsOverrides.PresentationOverride(
                        null,
                        ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC,
                        null,
                        new ScopedSettingsOverrides.PresentationOverride.PromptOverride(
                            null, null, 275L)),
                    new ScopedSettingsOverrides.MovementOverride(false),
                    new ScopedSettingsOverrides.WorldTopologyOverride(null, true))));

    sessionContextService.save(
        new SessionContext(
            999L,
            42L,
            100L,
            "player@example.com",
            55L,
            "Player",
            7L,
            "room-1",
            "jwt-token",
            "en-NZ",
            7L));

    String body =
        HttpTestSupport.getBodyUnchecked(
            "http://localhost:" + port + "/actuator/settings/effective?sessionId=999");

    assertThat(body).contains("\"persistedSession\":true");
    assertThat(body).contains("\"sessionId\":999");
    assertThat(body).contains("\"tenantId\":42");
    assertThat(body).contains("\"gameInstanceId\":7");
    assertThat(body).contains("\"briefEnabledByDefault\":true");
    assertThat(body).contains("\"defaultColorMode\":\"BASIC\"");
    assertThat(body).contains("\"coalesceWindowMs\":275");
    assertThat(body).contains("\"postMoveLookEnabled\":false");
    assertThat(body).contains("\"scopeModel\":\"REGION_AREA_AND_MAP\"");
    assertThat(body).contains("\"regionsEnabled\":true");
    assertThat(body).contains("\"sources\":[\"operatorDefaults\",\"tenantPersistedOverride:42\"");
    assertThat(body)
        .contains("\"sources\":[\"operatorDefaults\",\"gameInstancePersistedOverride:7\"]");
    assertThat(body).contains("\"resumeWindowMs\":240000");
  }
}
