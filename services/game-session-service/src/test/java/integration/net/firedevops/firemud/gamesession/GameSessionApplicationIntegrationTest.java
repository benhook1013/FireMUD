package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ResultStatus;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameDesignClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryRetentionSweepJob;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.impl.TickScheduler;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.test.FiremudAuthTestProperties;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
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
import tools.jackson.core.type.TypeReference;
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
@MockitoBean(types = {TickScheduler.class, PlayerCommandHistoryRetentionSweepJob.class})
class GameSessionApplicationIntegrationTest {
  private static final String PLATFORM_ADMIN_ACCOUNT_ID = "4f5c31f3-fac5-4cad-b8f2-bb53e5c75f01";
  private static final String OPAQUE_PUBLISHED_RELEASE_BUNDLE_REF =
      "6bf9e6d0-7f6b-4c85-b75e-d65949694f13";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String ACCOUNT_UUID = "5e1340f8-99c8-49fa-a4fe-5fc9d2075621";
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
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
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

  @org.springframework.beans.factory.annotation.Autowired
  private SessionContextService sessionContextService;

  @org.springframework.beans.factory.annotation.Autowired
  private GameInstanceRepository gameInstanceRepository;

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
    int existingGameInstances = gameInstanceRepository.findAll().size();

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

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("FAILED_PRECONDITION");
    assertThat(gameInstanceRepository.findAll()).hasSize(existingGameInstances);
    org.mockito.Mockito.verifyNoInteractions(worldManagementClient);
  }

  @Test
  void startSessionUsesRealHttpAndPersistencePath() throws Exception {
    when(gameDesignClient.resolveLaunchDescriptor(42L, 7L, "cp-1"))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.ResolveLaunchDescriptorResponse.newBuilder()
                .setLaunchDescriptor(
                    net.firedevops.firemud.gamedesign.v1.LaunchDescriptor.newBuilder()
                        .setLaunchDescriptorId("ld-1")
                        .setTenantId("42")
                        .setGameTemplateId(7L)
                        .setControlPlaneRequestId("cp-1")
                        .setVersionId(11L)
                        .setScriptPatchVersion("patch-1")
                        .setRuntimeFlagsJson("{}")
                        .setGenerationConfigRevision("genrev-11")
                        .setVersionStateEpoch(77L)
                        .setReleaseBundleId(77L)
                        .setPublishedReleaseBundleRef(OPAQUE_PUBLISHED_RELEASE_BUNDLE_REF)
                        .build())
                .build());
    when(gameDesignClient.getPublishedReleaseBundle(42L, 11L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetPublishedReleaseBundleResponse.newBuilder()
                .setBundle(
                    net.firedevops.firemud.gamedesign.v1.PublishedReleaseBundle.newBuilder()
                        .setId(77L)
                        .setVersionId(11L)
                        .setAttestationSchemaVersion("v1")
                        .setManifestHash("manifest-11")
                        .addRequiredManifestAssetKeys("manifest.json")
                        .setGenerationConfigRevision("genrev-11")
                        .setPublishedReleaseBundleRef(OPAQUE_PUBLISHED_RELEASE_BUNDLE_REF)
                        .build())
                .build());
    when(gameDesignClient.getVersionAssetArtifactState(42L, 11L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetVersionAssetArtifactStateResponse.newBuilder()
                .setArtifactState(
                    net.firedevops.firemud.gamedesign.v1.VersionAssetArtifactState.newBuilder()
                        .setTenantId("42")
                        .setVersionId(11L)
                        .setArtifactState(
                            net.firedevops.firemud.gamedesign.v1.ArtifactState
                                .ARTIFACT_STATE_PUBLISHED)
                        .setStateEpoch(2L)
                        .setManifestHash("manifest-11")
                        .addExportedManifestAssetKeys("manifest.json")
                        .build())
                .build());
    when(gameDesignClient.getVersionState(42L, 11L))
        .thenReturn(
            net.firedevops.firemud.gamedesign.v1.GetVersionStateResponse.newBuilder()
                .setVersionState(
                    net.firedevops.firemud.gamedesign.v1.VersionStateSnapshot.newBuilder()
                        .setTenantId("42")
                        .setVersionId(11L)
                        .setVersionState(
                            net.firedevops.firemud.gamedesign.v1.VersionLifecycleState
                                .VERSION_LIFECYCLE_STATE_PUBLISHED)
                        .setVersionStateEpoch(77L)
                        .setUpdatedAt("2026-04-15T10:00:00")
                        .build())
                .build());
    when(worldManagementClient.prepareWorldInstance(
            anyLong(),
            anyLong(),
            anyLong(),
            anyString(),
            anyString(),
            anyLong(),
            nullable(String.class),
            anyString(),
            anyString(),
            anyLong(),
            anyString(),
            anyLong(),
            any()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.PrepareWorldInstanceResponse.newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
                            .setGameTemplateId(Long.toString(invocation.getArgument(2, Long.class)))
                            .setControlPlaneRequestId(invocation.getArgument(3, String.class))
                            .setLaunchDescriptorId(invocation.getArgument(4, String.class))
                            .setVersionId(Long.toString(invocation.getArgument(5, Long.class)))
                            .setReleaseBundleId(
                                Long.toString(invocation.getArgument(9, Long.class)))
                            .setGenerationConfigRevision(invocation.getArgument(8, String.class))
                            .setPublishedReleaseBundleRef(invocation.getArgument(10, String.class))
                            .setVersionStateEpoch(invocation.getArgument(11, Long.class))
                            .setLifecycleEpoch(1L)
                            .setStatus(
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_PREPARING)
                            .build())
                    .build());
    when(worldManagementClient.activatePreparedWorldInstance(anyLong(), anyLong(), anyLong()))
        .thenAnswer(
            invocation ->
                net.firedevops.firemud.worldmanagement.v1.ActivatePreparedWorldInstanceResponse
                    .newBuilder()
                    .setWorldInstance(
                        net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot
                            .newBuilder()
                            .setTenantId(Long.toString(invocation.getArgument(0, Long.class)))
                            .setGameInstanceId(Long.toString(invocation.getArgument(1, Long.class)))
                            .setLifecycleEpoch(invocation.getArgument(2, Long.class) + 1L)
                            .setStatus(
                                net.firedevops.firemud.worldmanagement.v1
                                    .WorldInstanceLifecycleStatus
                                    .WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                            .build())
                    .build());
    StartSessionRequest request = new StartSessionRequest(42L, 7L, "cp-1", 100L);

    String responseBody =
        HttpTestSupport.postJsonBodyUnchecked(
            "http://localhost:" + port + "/sessions",
            OBJECT_MAPPER.writeValueAsString(request),
            privilegedHeaders());
    ApiResponse<GameInstanceDto> body =
        OBJECT_MAPPER.readValue(responseBody, new TypeReference<ApiResponse<GameInstanceDto>>() {});

    org.mockito.Mockito.verify(worldManagementClient)
        .prepareWorldInstance(
            eq(42L),
            anyLong(),
            eq(7L),
            eq("cp-1"),
            eq("ld-1"),
            eq(11L),
            org.mockito.ArgumentMatchers.isNull(),
            eq("{}"),
            eq("genrev-11"),
            eq(77L),
            eq(OPAQUE_PUBLISHED_RELEASE_BUNDLE_REF),
            eq(77L),
            any());

    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(ResultStatus.SUCCESS);
    assertThat(body.data()).isNotNull();
    assertThat(body.data().tenantId()).isEqualTo(42L);
    assertThat(body.data().runtimeVersion()).isEqualTo("11");
    assertThat(body.data().scriptPatchVersion()).isNull();
    assertThat(body.data().scriptPinEpoch()).isNull();
    assertThat(body.data().gameTemplateId()).isEqualTo(7L);
    assertThat(body.data().launchDescriptorId()).isEqualTo("ld-1");
    assertThat(body.data().versionId()).isEqualTo(11L);
    assertThat(body.data().releaseBundleId()).isEqualTo(77L);
    assertThat(body.data().versionStateEpoch()).isEqualTo(77L);
    assertThat(body.data().generationConfigRevision()).isEqualTo("genrev-11");
    assertThat(body.data().ownerAccountId()).isEqualTo(100L);
    assertThat(body.data().status()).isEqualTo("RUNNING");
    assertThat(body.data().id()).isPositive();
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
            ACCOUNT_UUID,
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
                PLATFORM_ADMIN_ACCOUNT_ID,
                Map.of(
                    "accountId",
                    PLATFORM_ADMIN_ACCOUNT_ID,
                    "globalRoles",
                    List.of("platformAdmin"))));
  }
}
