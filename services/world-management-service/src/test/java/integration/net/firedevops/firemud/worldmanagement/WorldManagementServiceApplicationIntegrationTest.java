package net.firedevops.firemud.worldmanagement;

import static net.firedevops.firemud.worldmanagement.jooq.tables.RegionInstance.REGION_INSTANCE;
import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldEvent.WORLD_EVENT;
import static net.firedevops.firemud.worldmanagement.jooq.tables.WorldInstance.WORLD_INSTANCE;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.entity.WorldEvent;
import net.firedevops.firemud.worldmanagement.repository.WorldEventRepository;
import org.jooq.DSLContext;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldManagementServiceApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @LocalServerPort private int port;
  @Autowired private JwtUtil jwtUtil;
  @Autowired private DSLContext dsl;
  @Autowired private WorldEventRepository worldEventRepository;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;
  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }

  @Test
  void listRegionsRejectsMalformedTenantIdWithInvalidArgumentEnvelope() throws Exception {
    String token =
        jwtUtil.generateToken("operator", Map.of("globalRoles", List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/regions?tenantId=bad-tenant"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be numeric\"");
  }

  @Test
  void moveRegionRejectsMalformedShardIdWithInvalidArgumentEnvelope() throws Exception {
    String token =
        jwtUtil.generateToken("operator", Map.of("globalRoles", List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/regions/4/move?tenantId=1&shardId=bad"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"shardId must be numeric\"");
  }

  @Test
  void saveRuleRejectsMalformedBodyWithInvalidArgumentEnvelope() throws Exception {
    String token =
        jwtUtil.generateToken("operator", Map.of("globalRoles", List.of("platformAdmin")));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/generation/rules"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {"tenantId":"bad","name":"room","scopeType":"ZONE_SUBTREE","scopeId":"12","value":"{}"}
                    """))
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"Request body is malformed\"");
  }

  @Test
  @Transactional
  void worldEventDueQueryIncludesInScopeWeatherAndEnforcesScope() {
    long exactRegionId = insertRegion(101L, 1001L, 11L);
    long crossTenantRegionId = insertRegion(202L, 1001L, 22L);
    long crossInstanceRegionId = insertRegion(101L, 1002L, 33L);
    LocalDateTime dueAt = LocalDateTime.now().minusMinutes(1);

    insertEvent(101L, 1001L, exactRegionId, "REGION_NOTICE", dueAt);
    insertEvent(101L, 1001L, crossTenantRegionId, "CROSS_TENANT_NOTICE", dueAt);
    insertEvent(101L, 1001L, crossInstanceRegionId, "CROSS_INSTANCE_NOTICE", dueAt);
    insertEvent(101L, 1001L, exactRegionId, "WEATHER_CHANGE", dueAt);
    insertEvent(101L, 1001L, null, "REGIONLESS_NOTICE", dueAt);

    List<WorldEvent> dueEvents = worldEventRepository.findDueEventsForShard(dueAt, 0);

    assertThat(dueEvents)
        .extracting(WorldEvent::getEventType)
        .containsExactlyInAnyOrder(
            "REGION_NOTICE", "REGIONLESS_NOTICE", "WEATHER_CHANGE");
    WorldEvent exactRegionEvent =
        dueEvents.stream()
            .filter(event -> "REGION_NOTICE".equals(event.getEventType()))
            .findFirst()
            .orElseThrow();
    assertThat(exactRegionEvent.getRegionInstance()).isNotNull();
    assertThat(exactRegionEvent.getRegionInstance().getId()).isEqualTo(exactRegionId);
    assertThat(exactRegionEvent.getRegionInstance().getTenantId()).isEqualTo(101L);
    assertThat(exactRegionEvent.getRegionInstance().getGameInstanceId()).isEqualTo(1001L);
    WorldEvent regionlessEvent =
        dueEvents.stream()
            .filter(event -> "REGIONLESS_NOTICE".equals(event.getEventType()))
            .findFirst()
            .orElseThrow();
    assertThat(regionlessEvent.getRegionInstance()).isNull();
  }

  private long insertRegion(long tenantId, long gameInstanceId, long fixtureLabel) {
    Long worldInstanceId =
        dsl.insertInto(WORLD_INSTANCE)
            .set(WORLD_INSTANCE.TENANT_ID, tenantId)
            .set(WORLD_INSTANCE.GAME_INSTANCE_ID, gameInstanceId)
            .set(WORLD_INSTANCE.GAME_TEMPLATE_ID, 1L)
            .set(WORLD_INSTANCE.CONTROL_PLANE_REQUEST_ID, "event-test-" + fixtureLabel)
            .set(WORLD_INSTANCE.LAUNCH_DESCRIPTOR_ID, "event-test-launch-" + fixtureLabel)
            .set(WORLD_INSTANCE.VERSION_ID, 1L)
            .set(WORLD_INSTANCE.GENERATION_CONFIG_REVISION, "event-test-generation")
            .set(WORLD_INSTANCE.RELEASE_BUNDLE_ID, 1L)
            .set(WORLD_INSTANCE.PUBLISHED_RELEASE_BUNDLE_REF, "event-test-release")
            .set(WORLD_INSTANCE.VERSION_STATE_EPOCH, 1L)
            .set(WORLD_INSTANCE.STATUS, "ACTIVE")
            .returning(WORLD_INSTANCE.ID)
            .fetchOne(WORLD_INSTANCE.ID);
    if (worldInstanceId == null) {
      throw new IllegalStateException("world instance insert did not return an id");
    }
    Long regionId =
        dsl.insertInto(REGION_INSTANCE)
            .set(REGION_INSTANCE.TENANT_ID, tenantId)
            .set(REGION_INSTANCE.GAME_INSTANCE_ID, gameInstanceId)
            .set(REGION_INSTANCE.WORLD_INSTANCE_ID, worldInstanceId)
            .set(REGION_INSTANCE.SHARD_ID, 0)
            .set(REGION_INSTANCE.NAME, "event-test-region-" + fixtureLabel)
            .returning(REGION_INSTANCE.ID)
            .fetchOne(REGION_INSTANCE.ID);
    if (regionId == null) {
      throw new IllegalStateException("region insert did not return an id");
    }
    return regionId;
  }

  private void insertEvent(
      long tenantId,
      long gameInstanceId,
      Long regionInstanceId,
      String eventType,
      LocalDateTime executeAt) {
    dsl.insertInto(WORLD_EVENT)
        .set(WORLD_EVENT.TENANT_ID, tenantId)
        .set(WORLD_EVENT.GAME_INSTANCE_ID, gameInstanceId)
        .set(WORLD_EVENT.REGION_INSTANCE_ID, regionInstanceId)
        .set(WORLD_EVENT.EVENT_TYPE, eventType)
        .set(WORLD_EVENT.EVENT_DATA, eventType)
        .set(WORLD_EVENT.EXECUTE_AT, executeAt)
        .execute();
  }
}
