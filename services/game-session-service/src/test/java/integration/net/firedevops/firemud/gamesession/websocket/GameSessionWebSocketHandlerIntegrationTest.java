package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.command.text.LookTextRenderer;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerMutation;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.testsupport.GameplayAsyncAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayStructuredCommandAssertions;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketDriver;
import net.firedevops.firemud.gamesession.testsupport.GameplayWebSocketScenarios;
import net.firedevops.firemud.gamesession.testsupport.InMemorySessionContextTestConfiguration;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.v1.GetWorldInstanceLifecycleResponse;
import net.firedevops.firemud.worldmanagement.v1.TerminateWorldInstanceResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleSnapshot;
import net.firedevops.firemud.worldmanagement.v1.WorldInstanceLifecycleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"removal", "resource"})
@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=test",
      "game-session.require-authenticated-commands=true",
      "firemud.database.enabled=true",
      "spring.data.redis.repositories.enabled=false",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0",
      "spring.flyway.enabled=true",
      "firemud.gateway.connect-context.jwt-secret=testsecretkeytestsecretkeytest1234",
      "firemud.gameplay.catalog.worlds[0].slug=demo",
      "firemud.gameplay.catalog.worlds[0].display-name=Demo World",
      "firemud.gameplay.catalog.worlds[0].realms[0].slug=production",
      "firemud.gameplay.catalog.worlds[0].realms[0].display-name=Live Realm",
      "firemud.gameplay.catalog.worlds[0].realms[0].tenant-id=22",
      "firemud.gameplay.catalog.worlds[0].realms[0].game-instance-id=1",
      "firemud.gameplay.catalog.worlds[0].realms[0].pointer-version=1",
      "firemud.gameplay.catalog.worlds[0].realms[0].visible=true",
      "firemud.gameplay.catalog.worlds[0].realms[0].requires-character-selection=false",
      "firemud.gameplay.catalog.worlds[1].slug=sandbox",
      "firemud.gameplay.catalog.worlds[1].display-name=Builder Sandbox",
      "firemud.gameplay.catalog.worlds[1].realms[0].slug=production",
      "firemud.gameplay.catalog.worlds[1].realms[0].display-name=Live Realm",
      "firemud.gameplay.catalog.worlds[1].realms[0].tenant-id=22",
      "firemud.gameplay.catalog.worlds[1].realms[0].game-instance-id=2",
      "firemud.gameplay.catalog.worlds[1].realms[0].pointer-version=1",
      "firemud.gameplay.catalog.worlds[1].realms[0].visible=true",
      "firemud.gameplay.catalog.worlds[1].realms[0].requires-character-selection=true",
    })
@ActiveProfiles("test")
@Import({NoGrpcServerTestConfiguration.class, InMemorySessionContextTestConfiguration.class})
class GameSessionWebSocketHandlerIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_session_service");
  }

  @LocalServerPort private int port;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean private AccountClient accountClient;

  @MockitoBean private EntityManagementClient entityManagementClient;

  @MockitoBean private GameInstanceRepository gameInstanceRepository;

  @MockitoBean private GameLogicClient gameLogicClient;

  @MockitoBean private WorldManagementClient worldManagementClient;

  @MockitoBean private ModerationPolicyClient moderationPolicyClient;

  @MockitoBean private LookTextRenderer lookTextRenderer;

  @MockitoBean private CommandService commandService;

  @MockitoBean private LookCacheService lookCacheService;

  @MockitoBean private ScreenBufferService screenBufferService;

  @MockitoBean private RedisConnectionFactory redisConnectionFactory;

  @MockitoBean private RedisTemplate<String, Object> redisTemplate;

  @MockitoBean private ValueOperations<String, Object> redisValueOperations;

  @MockitoBean private SetOperations<String, Object> redisSetOperations;

  @Autowired private SessionContextService sessionContextService;

  @Autowired private AccountRecentPresenceService accountRecentPresenceService;

  @Autowired private GameplayPresenceService gameplayPresenceService;

  @Autowired
  private GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;

  @Autowired private GameplayAdmissionPointerRepository gameplayAdmissionPointerRepository;

  @Autowired
  private GameplayAdmissionPointerEventRepository gameplayAdmissionPointerEventRepository;

  private final ConcurrentMap<String, Object> firstPartyConnectStore = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, java.util.LinkedHashSet<Object>> redisSetStore =
      new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    firstPartyConnectStore.clear();
    redisSetStore.clear();
    sessionContextService.deleteBySessionId(22L, 41L);
    sessionContextService.deleteBySessionId(22L, 42L);
    sessionContextService.deleteBySessionId(22L, 1L);
    sessionContextService.deleteBySessionId(22L, 2L);
    resetAdmissionPointers();
    when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
    when(redisTemplate.opsForSet()).thenReturn(redisSetOperations);
    when(redisValueOperations.get(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> firstPartyConnectStore.get(invocation.getArgument(0)));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              firstPartyConnectStore.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(redisValueOperations)
        .set(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              firstPartyConnectStore.remove(invocation.getArgument(0));
              return null;
            })
        .when(redisTemplate)
        .delete(org.mockito.ArgumentMatchers.anyString());
    when(gameInstanceRepository.save(org.mockito.ArgumentMatchers.any(GameInstance.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              redisSetStore
                  .computeIfAbsent(
                      invocation.getArgument(0), ignored -> new java.util.LinkedHashSet<>())
                  .add(invocation.getArgument(1));
              return 1L;
            })
        .when(redisSetOperations)
        .add(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              java.util.LinkedHashSet<Object> members =
                  redisSetStore.get(invocation.getArgument(0));
              if (members == null) {
                return 0L;
              }
              return members.remove(invocation.getArgument(1)) ? 1L : 0L;
            })
        .when(redisSetOperations)
        .remove(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    when(redisSetOperations.members(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              java.util.LinkedHashSet<Object> members =
                  redisSetStore.get(invocation.getArgument(0));
              return members == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(members);
            });
    when(moderationPolicyClient.evaluateGameplayAdmission(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(
            net.firedevops.firemud.loggingadmin.v1.EvaluateModerationPolicyResponse.newBuilder()
                .setAllowed(true)
                .build());

    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1021").build())
            .setRoomName("Login Hall")
            .setShortDescription("A narrow testing hall")
            .setLongDescription("A narrow testing hall used for login verification.")
            .build();
    when(accountClient.authenticate(eq("22"), eq("demo@example.com"), eq("swordfish"), eq("")))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setAuthToken("stub-token")
                .setAccountId("123")
                .build());
    org.mockito.Mockito.doReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build())
        .when(accountClient)
        .getTenantMembershipForRuntime(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class));
    org.mockito.Mockito.doReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build())
        .when(accountClient)
        .getTenantEntitlementsForRuntime(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class));
    org.mockito.Mockito.doReturn(
            ListCharactersByAccountResponse.newBuilder()
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("123")
                        .setTenantId("22")
                        .setAccountId("123")
                        .setName("Emberline")
                        .setLevel(12)
                        .build())
                .addCharacters(
                    net.firedevops.firemud.entitymanagement.v1.Character.newBuilder()
                        .setId("456")
                        .setTenantId("22")
                        .setAccountId("123")
                        .setName("Sora")
                        .setLevel(7)
                        .build())
                .build())
        .when(entityManagementClient)
        .listCharactersByAccount(
            eq("22"), eq("123"), eq("1"), eq(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(commandService.enqueue(org.mockito.ArgumentMatchers.anyString(), eq("LOGIN"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(
            org.mockito.ArgumentMatchers.anyString(), eq("PLAY demo"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(org.mockito.ArgumentMatchers.anyString(), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("41"), eq("LOGIN demo@example.com swordfish"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("41"), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("41"), eq("QUICKLOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("41"), eq("AFK"), eq(false)))
        .thenAnswer(
            invocation -> {
              gameplayPresenceService.setExplicitAfk(41L, true);
              return CommandEnqueueResult.success();
            });
    when(commandService.enqueue(eq("42"), eq("LOGIN demo@example.com swordfish"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("42"), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("1"), eq("PLAY demo"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("1"), eq("AFK"), eq(false)))
        .thenAnswer(
            invocation -> {
              gameplayPresenceService.setExplicitAfk(1L, true);
              return CommandEnqueueResult.success();
            });
    when(commandService.enqueue(eq("1"), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(gameLogicClient.resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 41L, 123L, 1L, "1021")), eq("1021"), eq("")))
        .thenReturn(lookResult);
    when(gameLogicClient.resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 41L, 123L, 1L, "1021")), eq("1021"), eq("fr")))
        .thenReturn(lookResult);
    when(gameLogicClient.resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 42L, 123L, 1L, "1021")), eq("1021"), eq("")))
        .thenReturn(lookResult);
    when(gameLogicClient.resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 1L, 123L, 1L, "1021")), eq("1021"), eq("")))
        .thenReturn(lookResult);
    when(gameLogicClient.resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 2L, 123L, 1L, "1021")), eq("1021"), eq("")))
        .thenReturn(lookResult);
    when(lookTextRenderer.toPlayerOutput(
            eq(lookResult),
            eq(true),
            any(net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class)))
        .thenReturn(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.from(
                    lookResult,
                    true,
                    net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                        .EXPLICIT_LOOK,
                    net.firedevops.firemud.gamesession.presentation.LookViewOutput
                        .BriefRenderingHint.FOLLOW_DEFAULT)));
    when(lookTextRenderer.toPlayerOutput(
            eq(lookResult),
            eq(false),
            any(net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason.class),
            any(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .class)))
        .thenReturn(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.from(
                    lookResult,
                    false,
                    net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                        .QUICKLOOK,
                    net.firedevops.firemud.gamesession.presentation.LookViewOutput
                        .BriefRenderingHint.PREFER_BRIEF)));
    when(screenBufferService.get(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(Optional.empty());
    GameInstance instance = new GameInstance();
    instance.setId(41L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    org.mockito.Mockito.doReturn(Optional.of(instance)).when(gameInstanceRepository).findById(41L);
    instance = new GameInstance();
    instance.setId(42L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    org.mockito.Mockito.doReturn(Optional.of(instance)).when(gameInstanceRepository).findById(42L);
    instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    org.mockito.Mockito.doReturn(Optional.of(instance)).when(gameInstanceRepository).findById(1L);
    instance = new GameInstance();
    instance.setId(2L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    org.mockito.Mockito.doReturn(Optional.of(instance)).when(gameInstanceRepository).findById(2L);
    when(worldManagementClient.getWorldInstanceLifecycle(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
        .thenAnswer(
            invocation -> {
              long tenantId = invocation.getArgument(0);
              long gameInstanceId = invocation.getArgument(1);
              return GetWorldInstanceLifecycleResponse.newBuilder()
                  .setWorldInstance(
                      WorldInstanceLifecycleSnapshot.newBuilder()
                          .setTenantId(Long.toString(tenantId))
                          .setGameInstanceId(Long.toString(gameInstanceId))
                          .setLifecycleEpoch(2L)
                          .setStatus(
                              WorldInstanceLifecycleStatus.WORLD_INSTANCE_LIFECYCLE_STATUS_ACTIVE)
                          .build())
                  .build();
            });
    when(worldManagementClient.terminateWorldInstance(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              long tenantId = invocation.getArgument(0);
              long gameInstanceId = invocation.getArgument(1);
              long expectedLifecycleEpoch = invocation.getArgument(2);
              return TerminateWorldInstanceResponse.newBuilder()
                  .setWorldInstance(
                      WorldInstanceLifecycleSnapshot.newBuilder()
                          .setTenantId(Long.toString(tenantId))
                          .setGameInstanceId(Long.toString(gameInstanceId))
                          .setLifecycleEpoch(expectedLifecycleEpoch + 1L)
                          .setStatus(
                              WorldInstanceLifecycleStatus
                                  .WORLD_INSTANCE_LIFECYCLE_STATUS_TERMINATED)
                          .build())
                  .build();
            });
  }

  @Test
  void websocketLoginThenLookUsesAuthenticatedPath() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("41")) {
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      payloads = client.responses();
    }

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads)
        .anyMatch(payload -> payload.startsWith("OK PLAY") && payload.endsWith("> "));
    assertThat(payloads)
        .anyMatch(
            payload ->
                payload.startsWith("OK LOOK")
                    && payload.contains("Room: Login Hall")
                    && payload.contains("Long: A narrow testing hall used for login verification.")
                    && payload.endsWith("> "));
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 41L))
        .hasValueSatisfying(
            context -> {
              assertThat(context.gameInstanceId()).isEqualTo(1L);
              assertThat(context.characterId()).isEqualTo(123L);
              assertThat(context.roomInstanceId()).isNotBlank();
            });

    verify(commandService).enqueue("41", "LOGIN demo@example.com swordfish", false);
    verify(commandService).enqueue("41", "LOOK", false);
    verify(gameLogicClient)
        .resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 41L, 123L, 1L, "1021")), eq("1021"), eq(""));
    verify(lookCacheService)
        .cache(
            eq(22L),
            eq(1L),
            eq("1021"),
            eq(
                "Room: Login Hall (ID: 1021)\n"
                    + "Short: A narrow testing hall\n"
                    + "Long: A narrow testing hall used for login verification.\n"
                    + "Exits: \n"
                    + "Entities:"),
            eq(
                "OK LOOK\n"
                    + "Room: Login Hall (ID: 1021)\n"
                    + "Short: A narrow testing hall\n"
                    + "Long: A narrow testing hall used for login verification.\n"
                    + "Exits: \n"
                    + "Entities:\n\n"));
  }

  @Test
  void repeatedLookStillShowsPromptInsideBurstWindow() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("41")) {
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      client.send("LOOK");
      client.awaitMatching(
          payload ->
              client.responses().stream()
                      .filter(response -> response.startsWith("OK LOOK") && response.endsWith("> "))
                      .count()
                  >= 2,
          "two prompt-terminated LOOK responses");
      payloads = client.responses();
    }

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
    assertThat(
            payloads.stream()
                .filter(payload -> payload.startsWith("OK LOOK") && payload.endsWith("> ")))
        .hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void websocketQuickLookUsesDistinctCommandLabelAndPrompt() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("41")) {
      client.send("QUICKLOOK");
      client.awaitStartsWith("OK QUICKLOOK");
      payloads = client.responses();
    }

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
    assertThat(payloads)
        .anyMatch(
            payload ->
                payload.startsWith("OK QUICKLOOK")
                    && payload.endsWith("> ")
                    && payload.contains("Room: Login Hall")
                    && !payload.contains("Long:"));
  }

  @Test
  void websocketLogoutClearsReplayStateAndClosesTransport() throws Exception {
    GameplayWebSocketDriver.CloseEvent closeEvent;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("41")) {
      client.send("LOGOUT");
      closeEvent = client.awaitClosed();
    }

    assertThat(closeEvent.statusCode()).isEqualTo(CloseStatus.NORMAL.getCode());
    assertThat(closeEvent.reason()).isEqualTo("LOGOUT");
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 41L)).isEmpty();
    verify(screenBufferService).clear(22L, 1L, 123L);
    verify(commandService, never()).enqueue("41", "LOGOUT", false);
  }

  @Test
  void freshLoginAfterLogoutDoesNotReplayStaleReconnectBuffer() throws Exception {
    when(screenBufferService.get(eq(22L), eq(1L), eq(123L)))
        .thenReturn(
            Optional.of(
                new ScreenBufferService.BufferedScreen(
                    java.util.List.of(
                        ScreenBufferService.BufferedEntry.fromText(
                            "STALE REPLAY SHOULD NOT APPEAR")),
                    1,
                    1,
                    System.currentTimeMillis())));

    performLogoutFlow("41");

    List<String> secondPayloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("42")) {
      secondPayloads = client.responses();
    }

    assertThat(secondPayloads)
        .noneMatch(payload -> payload.contains("STALE REPLAY SHOULD NOT APPEAR"));
    verify(screenBufferService, never()).get(22L, 1L, 123L);
  }

  @Test
  void unexpectedDisconnectKeepsReplayEligibleForFreshReconnect() throws Exception {
    clearInvocations(screenBufferService);
    when(screenBufferService.get(eq(22L), eq(1L), eq(123L)))
        .thenReturn(
            Optional.of(
                new ScreenBufferService.BufferedScreen(
                    java.util.List.of(
                        ScreenBufferService.BufferedEntry.fromText("RECONNECT REPLAY APPEARS")),
                    1,
                    1,
                    System.currentTimeMillis())));

    GameplayWebSocketDriver firstClient = openAdmittedGameplayDriver("41");
    firstClient.abort();
    GameplayAsyncAssertions.assertPresenceCountEventually(
        gameplayPresenceService, 22L, 1L, 0, java.time.Duration.ofSeconds(5));
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 41L)).isPresent();
    assertThat(accountRecentPresenceService.findByAccountIds(22L, List.of(123L))).containsKey(123L);

    List<String> secondPayloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("42")) {
      client.awaitContains("RECONNECT REPLAY APPEARS");
      secondPayloads = client.responses();
      GameplayAsyncAssertions.assertPresenceCountEventually(
          gameplayPresenceService, 22L, 1L, 1, java.time.Duration.ofSeconds(5));
      assertThat(gameplayPresenceService.listConnectedByGameInstance(22L, 1L))
          .anySatisfy(presence -> assertThat(presence.sessionId()).isEqualTo(42L));
    }
    assertThat(secondPayloads).anyMatch(payload -> payload.contains("RECONNECT REPLAY APPEARS"));
    assertThat(secondPayloads).anyMatch(payload -> payload.startsWith("OK PLAY"));
    verify(screenBufferService, atLeastOnce()).get(22L, 1L, 123L);
    verify(screenBufferService, never()).clear(22L, 1L, 123L);
  }

  @Test
  void websocketAfkCommandUpdatesLiveGameplayPresence() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("41")) {
      client.send("AFK");
      GameplayAsyncAssertions.assertEventually(
          "AFK command enqueue",
          java.time.Duration.ofSeconds(5),
          () -> {
            return Mockito.mockingDetails(commandService).getInvocations().stream()
                .anyMatch(
                    invocation ->
                        invocation.getMethod().getName().equals("enqueue")
                            && invocation.getArguments().length == 3
                            && "41".equals(invocation.getArguments()[0])
                            && "AFK".equals(invocation.getArguments()[1])
                            && Boolean.FALSE.equals(invocation.getArguments()[2]));
          });
      GameplayAsyncAssertions.assertEventually(
          "AFK presence state",
          java.time.Duration.ofSeconds(5),
          () ->
              gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
                  .anyMatch(entry -> entry.sessionId() == 41L));
      GameplayAsyncAssertions.assertEventually(
          "AFK explicit timestamp",
          java.time.Duration.ofSeconds(5),
          () ->
              gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
                  .filter(entry -> entry.sessionId() == 41L)
                  .findFirst()
                  .map(GameplayPresence::explicitAfkSinceEpochMs)
                  .isPresent());
      payloads = client.responses();
    }
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK PLAY"));
  }

  @Test
  void websocketLocaleHeaderAppliesToBuiltInLookRendering() throws Exception {
    LookResult lookResult =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("1707").build())
            .setRoomName("Galerie")
            .setShortDescription("Un couloir etroit file vers le sud.")
            .setLongDescription("Des lampes fument sous les arches de pierre.")
            .addExits(
                net.firedevops.firemud.gamelogic.v1.LookExit.newBuilder()
                    .setLabel("SUD")
                    .setTargetRoomInstanceId("R-1708")
                    .setDescription("porte etroite")
                    .build())
            .build();
    when(gameLogicClient.resolveLook(
            argThat(ctx -> matchesContext(ctx, 22L, 41L, 123L, 1L, "1021")), eq("1021"), eq("fr")))
        .thenReturn(lookResult);
    when(lookTextRenderer.toPlayerOutput(
            eq(lookResult),
            eq(true),
            eq(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                    .EXPLICIT_LOOK),
            eq(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .FOLLOW_DEFAULT)))
        .thenReturn(
            net.firedevops.firemud.gamesession.presentation.PlayerOutput.view(
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.from(
                    lookResult,
                    true,
                    net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                        .EXPLICIT_LOOK)));

    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openAdmittedGameplayDriver("41", java.util.Map.of("X-Firemud-Locale", "fr"))) {
      client.send("LOOK");
      client.awaitContains("Salle : Galerie");
      payloads = client.responses();
    }

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
    assertThat(payloads)
        .anyMatch(
            payload ->
                payload.contains("Salle : Galerie")
                    && payload.contains("Court : Un couloir etroit file vers le sud."));
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 41L))
        .hasValueSatisfying(context -> assertThat(context.localeTag()).isEqualTo("fr"));
  }

  @Test
  void websocketMoveEnqueuesDurableCommandAfterPlay() throws Exception {
    when(commandService.enqueue(eq("42"), eq("north"), eq(false)))
        .thenReturn(CommandEnqueueResult.success("cmd-move-1"));
    List<String> payloads;
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver("42")) {
      client.send("north");
      GameplayAsyncAssertions.assertEventually(
          "movement command enqueue",
          java.time.Duration.ofSeconds(5),
          () -> {
            return org.mockito.Mockito.mockingDetails(commandService).getInvocations().stream()
                .anyMatch(
                    invocation ->
                        "enqueue".equals(invocation.getMethod().getName())
                            && java.util.List.of(invocation.getArguments())
                                .equals(java.util.List.of("42", "north", false)));
          });
      payloads = client.responses();
    }

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(2);
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK PLAY"));
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 42L))
        .hasValueSatisfying(context -> assertThat(context.roomInstanceId()).isEqualTo("1021"));
  }

  @Test
  void websocketFirstPartyLoginConsumesVerifiedConnectContext() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("1", firstPartyClaims("demo", "production", "1", "1", "1"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("PLAY demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      payloads = client.responses();
    }

    assertThat(json(payloads.get(0)).path("commandType").asText()).isEqualTo("LOGIN");
    assertThat(json(payloads.get(0)).path("accepted").asBoolean()).isTrue();
    assertThat(payloads)
        .anyMatch(
            payload ->
                json(payload).path("commandType").asText().equals("PLAY")
                    && json(payload).path("accepted").asBoolean());
    assertThat(payloads).anyMatch(payload -> json(payload).path("outputs").isArray());
    verify(accountClient)
        .getTenantMembershipForRuntime(
            eq("123"), eq("22"), org.mockito.ArgumentMatchers.anyString());
    verify(accountClient)
        .getTenantEntitlementsForRuntime(eq("22"), org.mockito.ArgumentMatchers.anyString());
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 1L))
        .hasValueSatisfying(
            context -> {
              assertThat(context.bootstrapGameInstanceId()).isEqualTo(1L);
              assertThat(context.worldSlug()).isEqualTo("demo");
              assertThat(context.realmSlug()).isEqualTo("production");
              assertThat(context.pointerVersion()).isEqualTo(1L);
              assertThat(context.connectScopeId()).isEqualTo("scope-1");
              assertThat(context.connectRequestId()).isEqualTo("connect-req-1");
            });
  }

  @Test
  void websocketLoginCanBrowseRealmsAndCharactersBeforePlay() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client = openGameplayDriver("41")) {
      client.login("demo@example.com", "swordfish");
      client.send("REALMS demo");
      client.awaitStartsWith("OK REALMS");
      client.send("CHARS demo");
      client.awaitStartsWith("OK CHARS");
      payloads = client.responses();
    }

    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads)
        .anyMatch(
            payload ->
                payload.startsWith("OK REALMS")
                    && payload.contains("Live Realm (production) [shared, allow_new]"));
    assertThat(payloads)
        .anyMatch(
            payload ->
                payload.startsWith("OK CHARS")
                    && payload.contains("Emberline [lvl 12]")
                    && payload.contains("Sora [lvl 7]")
                    && payload.contains("Realm state: shared, creation: allow_new"));
    verify(commandService).enqueue("41", "LOGIN demo@example.com swordfish", false);
    verify(commandService, never()).enqueue("41", "REALMS demo", false);
    verify(commandService, never()).enqueue("41", "CHARS demo", false);
    verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void websocketFirstPartyStructuredLobbyBrowseIncludesRealmAndCharacterViews() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("1", firstPartyClaims("demo", "production", "1", "1", "browse-1"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("REALMS demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "REALMS"), "structured REALMS result");
      client.send("CHARS demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "CHARS"), "structured CHARS result");
      payloads = client.responses();
    }

    assertThat(payloads).anyMatch(payload -> isStructuredCommand(payload, "LOGIN"));
    JsonNode realmsResult =
        payloads.stream()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .filter(
                payload ->
                    "command_result".equals(payload.path("eventType").asText())
                        && "REALMS".equals(payload.path("commandType").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(realmsResult.path("accepted").asBoolean()).isTrue();
    assertThat(realmsResult.path("outputs").get(0).path("payloadType").asText())
        .isEqualTo("realms_view");
    assertThat(realmsResult.path("outputs").get(0).path("payload").path("worldSlug").asText())
        .isEqualTo("demo");
    assertThat(realmsResult.path("outputs").get(0).path("payload").path("realms")).hasSize(1);
    assertThat(
            realmsResult
                .path("outputs")
                .get(0)
                .path("payload")
                .path("realms")
                .get(0)
                .path("stateScope")
                .asText())
        .isEqualTo("SHARED");
    assertThat(
            realmsResult
                .path("outputs")
                .get(0)
                .path("payload")
                .path("realms")
                .get(0)
                .path("characterCreationPolicy")
                .asText())
        .isEqualTo("ALLOW_NEW");

    JsonNode charsResult =
        payloads.stream()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .filter(
                payload ->
                    "command_result".equals(payload.path("eventType").asText())
                        && "CHARS".equals(payload.path("commandType").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(charsResult.path("accepted").asBoolean()).isTrue();
    assertThat(charsResult.path("outputs").get(0).path("payloadType").asText())
        .isEqualTo("characters_view");
    assertThat(charsResult.path("outputs").get(0).path("payload").path("realmSlug").asText())
        .isEqualTo("production");
    assertThat(charsResult.path("outputs").get(0).path("payload").path("stateScope").asText())
        .isEqualTo("SHARED");
    assertThat(
            charsResult
                .path("outputs")
                .get(0)
                .path("payload")
                .path("characterCreationPolicy")
                .asText())
        .isEqualTo("ALLOW_NEW");
    assertThat(charsResult.path("outputs").get(0).path("payload").path("characters")).hasSize(2);
    verify(entityManagementClient)
        .listCharactersByAccount("22", "123", "1", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED);
  }

  @Test
  void websocketFirstPartyStructuredWhoIncludesActivityState() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("1", firstPartyClaims("demo", "production", "1", "1", "who-1"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("PLAY demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      client.send("AFK");
      GameplayAsyncAssertions.assertEventually(
          "AFK gameplay presence update",
          java.time.Duration.ofSeconds(5),
          () ->
              gameplayPresenceService.listConnectedByGameInstance(22L, 1L).stream()
                      .filter(entry -> entry.sessionId() == 1L)
                      .findFirst()
                      .orElseThrow()
                      .explicitAfkSinceEpochMs()
                  != null);
      client.send("WHO");
      client.awaitMatching(payload -> isStructuredCommand(payload, "WHO"), "structured WHO result");
      payloads = client.responses();
    }

    JsonNode whoResult =
        payloads.stream()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .filter(
                payload ->
                    "command_result".equals(payload.path("eventType").asText())
                        && "WHO".equals(payload.path("commandType").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(whoResult.path("accepted").asBoolean()).isTrue();
    assertThat(whoResult.path("outputs").get(0).path("payloadType").asText()).isEqualTo("who_view");
    assertThat(whoResult.path("outputs").get(0).path("payload").path("players")).hasSize(1);
    assertThat(
            whoResult
                .path("outputs")
                .get(0)
                .path("payload")
                .path("players")
                .get(0)
                .path("activityState")
                .asText())
        .isEqualTo("EXPLICIT_AFK");
  }

  @Test
  void websocketFirstPartyReconnectReplaysBufferedScreenAndFreshLookAfterPlay() throws Exception {
    when(screenBufferService.get(eq(22L), eq(1L), eq(123L)))
        .thenReturn(
            Optional.of(
                new ScreenBufferService.BufferedScreen(
                    java.util.List.of(
                        ScreenBufferService.BufferedEntry.fromText("Recent combat line\n"),
                        ScreenBufferService.BufferedEntry.fromText("Second recent line\n")),
                    2,
                    2,
                    32L)));

    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("1", firstPartyClaims("demo", "production", "1", "1", "2"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("PLAY demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      client.awaitMatching(
          payload ->
              "transcript_chunk".equals(json(payload).path("eventType").asText())
                  && payload.contains("Recent combat line"),
          "replayed transcript chunk");
      client.awaitMatching(
          payload ->
              "player_output".equals(json(payload).path("eventType").asText())
                  && containsKind(json(payload), "VIEW"),
          "fresh view output");
      client.awaitMatching(
          payload ->
              "player_output".equals(json(payload).path("eventType").asText())
                  && containsKind(json(payload), "PROMPT"),
          "fresh prompt output");
      payloads = client.responses();
    }

    assertThat(isStructuredCommand(payloads.get(0), "LOGIN")).isTrue();
    assertThat(payloads).anyMatch(payload -> isStructuredCommand(payload, "PLAY"));
    assertThat(payloads)
        .anyMatch(
            payload ->
                "transcript_chunk".equals(json(payload).path("eventType").asText())
                    && payload.contains("Recent combat line"));
    assertThat(payloads)
        .anyMatch(
            payload ->
                "player_output".equals(json(payload).path("eventType").asText())
                    && containsKind(json(payload), "VIEW"));
    assertThat(payloads)
        .anyMatch(
            payload ->
                "player_output".equals(json(payload).path("eventType").asText())
                    && containsKind(json(payload), "PROMPT"));
  }

  @Test
  void websocketFirstPartyLogoutClearsReplayStateBeforeFreshReconnect() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean cleared =
        new java.util.concurrent.atomic.AtomicBoolean();
    when(screenBufferService.get(eq(22L), eq(1L), eq(123L)))
        .thenAnswer(
            invocation ->
                cleared.get()
                    ? Optional.empty()
                    : Optional.of(
                        new ScreenBufferService.BufferedScreen(
                            java.util.List.of(
                                ScreenBufferService.BufferedEntry.fromText(
                                    "First-party stale replay\n")),
                            1,
                            1,
                            44L)));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              cleared.set(true);
              return null;
            })
        .when(screenBufferService)
        .clear(22L, 1L, 123L);

    GameplayWebSocketDriver.CloseEvent firstCloseEvent;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("1", firstPartyClaims("demo", "production", "1", "1", "logout-1"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("PLAY demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      client.send("LOGOUT");
      firstCloseEvent = client.awaitClosed();
    }

    assertThat(firstCloseEvent.reason()).isEqualTo("LOGOUT");
    assertThat(cleared.get()).isTrue();
    GameplayAsyncAssertions.assertPresenceCountEventually(
        gameplayPresenceService, 22L, 1L, 0, java.time.Duration.ofSeconds(5));
    assertThat(accountRecentPresenceService.findByAccountIds(22L, List.of(123L))).containsKey(123L);

    java.util.List<String> secondPayloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("2", firstPartyClaims("demo", "production", "1", "1", "logout-2"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("PLAY demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      secondPayloads = client.responses();
    }

    assertThat(secondPayloads).anyMatch(payload -> isStructuredCommand(payload, "LOGIN"));
    assertThat(secondPayloads).anyMatch(payload -> isStructuredCommand(payload, "PLAY"));
    assertThat(secondPayloads)
        .noneMatch(
            payload ->
                "transcript_chunk".equals(json(payload).path("eventType").asText())
                    && payload.contains("First-party stale replay"));
  }

  @Test
  void websocketFreshPlayDoesNotReplayBufferedLookFromOldSession() throws Exception {
    when(screenBufferService.get(eq(22L), eq(41L), eq(123L)))
        .thenReturn(
            Optional.of(
                new ScreenBufferService.BufferedScreen(
                    java.util.List.of(
                        ScreenBufferService.BufferedEntry.fromText(
                            "OK LOOK\nStale buffered look\n\n")),
                    1,
                    3,
                    24L)));

    List<String> payloads;
    try (GameplayWebSocketDriver client = openGameplayDriver("41")) {
      client.login("demo@example.com", "swordfish");
      client.play("demo");
      payloads = client.responses();
    }

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(2);
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK PLAY"));
    assertThat(payloads).noneMatch(payload -> payload.contains("Stale buffered look"));
    assertThat(payloads.stream().filter(payload -> payload.startsWith("OK LOOK"))).isEmpty();
  }

  @Test
  void websocketFailedReloginFailsClosedWithoutLeakingOldAuthenticatedState() throws Exception {
    when(accountClient.authenticate(eq("22"), eq("demo@example.com"), eq("wrongpass"), eq("")))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("UNAUTHENTICATED")
                        .setMessage("Invalid credentials")
                        .build())
                .build());

    List<String> payloads;
    try (GameplayWebSocketDriver client = openGameplayDriver("41")) {
      client.login("demo@example.com", "swordfish");
      client.play("demo");
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      client.send("LOGIN demo@example.com wrongpass");
      client.awaitStartsWith("ERROR INVALID_CREDENTIALS");
      client.send("LOOK");
      client.awaitStartsWith("ERROR LOGIN_REQUIRED");
      payloads = client.responses();
    }

    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK PLAY"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("ERROR INVALID_CREDENTIALS"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("ERROR LOGIN_REQUIRED"));
  }

  @Test
  void websocketActiveGameplaySessionFallsBackToPlayRequiredWhenPointerAdvances() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client = openGameplayDriver("41")) {
      client.login("demo@example.com", "swordfish");
      client.play("demo");
      client.send("LOOK");
      client.awaitStartsWith("OK LOOK");
      bumpProductionAdmissionPointer(1L, false);
      client.send("LOOK");
      client.awaitStartsWith("ERROR PLAY_REQUIRED");
      client.send("CHARS demo");
      client.awaitStartsWith("OK CHARS");
      payloads = client.responses();
    }

    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK PLAY"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("ERROR PLAY_REQUIRED"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK CHARS"));
  }

  @Test
  void websocketGenericRouteChangeOnReusedTransportClearsOldStateAndRefreshesBootstrapShell()
      throws Exception {
    try (GameplayWebSocketDriver first =
        openGameplayDriver(
            "41",
            java.util.Map.of(
                "X-Tenant-Id", "22",
                "X-Firemud-Transport-Session-Id", "41",
                "X-World-Slug", "demo",
                "X-Realm-Slug", "production",
                "X-Pointer-Version", "1"))) {
      first.login("demo@example.com", "swordfish");
      first.play("demo");
      first.send("LOOK");
      first.awaitStartsWith("OK LOOK");
    }

    List<String> payloads;
    try (GameplayWebSocketDriver second =
        openGameplayDriver(
            "42",
            java.util.Map.of(
                "X-Game-Instance-Id", "2",
                "X-Tenant-Id", "22",
                "X-Firemud-Transport-Session-Id", "41",
                "X-World-Slug", "sandbox",
                "X-Realm-Slug", "production",
                "X-Pointer-Version", "1"))) {
      second.send("LOOK");
      second.awaitStartsWith("ERROR LOGIN_REQUIRED");
      second.login("demo@example.com", "swordfish");
      second.awaitStartsWith("OK LOGIN");
      second.send("REALMS sandbox");
      second.awaitStartsWith("OK REALMS");
      payloads = second.responses();
    }

    assertThat(payloads).anyMatch(payload -> payload.startsWith("ERROR LOGIN_REQUIRED"));
    assertThat(payloads).anyMatch(payload -> payload.startsWith("OK LOGIN"));
    assertThat(payloads)
        .anyMatch(
            payload ->
                payload.startsWith("OK REALMS") && payload.contains("Live Realm (production)"));
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 41L))
        .hasValueSatisfying(
            context -> {
              assertThat(context.accountId()).isEqualTo(123L);
              assertThat(context.gameInstanceId()).isZero();
              assertThat(context.characterId()).isZero();
              assertThat(context.bootstrapGameInstanceId()).isEqualTo(2L);
              assertThat(context.worldSlug()).isEqualTo("sandbox");
              assertThat(context.realmSlug()).isEqualTo("production");
              assertThat(context.pointerVersion()).isEqualTo(1L);
            });
    assertThat(gameplayPresenceService.findConnectedBySessionId(41L)).isEmpty();
  }

  @Test
  void websocketFirstPartyInvalidConnectContextClosesImmediately() throws Exception {
    GameplayWebSocketDriver.CloseEvent closeEvent;
    try (GameplayWebSocketDriver client =
        GameplayWebSocketDriver.connect(
            websocketUri(),
            java.time.Duration.ofSeconds(10),
            java.util.Map.of(
                "X-Firemud-Connection-Mode", "first_party_web",
                "X-Firemud-Connect-Context", "not-a-valid-context"))) {
      closeEvent = client.awaitClosed();
    }

    assertThat(closeEvent.statusCode()).isEqualTo(1008);
    assertThat(closeEvent.reason()).isEqualTo("CONNECT_CONTEXT_INVALID");
  }

  @Test
  void websocketFirstPartyPlayRejectsScopeMismatch() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver(
            "2", firstPartyClaims("sandbox", "production", "1", "1", "mismatch"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      client.send("PLAY sandbox Sora");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      payloads = client.responses();
    }

    JsonNode loginFailure = json(payloads.get(0));
    assertThat(loginFailure.path("eventType").asText()).isEqualTo("command_result");
    assertThat(loginFailure.path("commandType").asText()).isEqualTo("LOGIN");
    assertThat(loginFailure.path("accepted").asBoolean()).isFalse();
    assertThat(loginFailure.path("errorCode").asText()).isEqualTo("CONNECT_SCOPE_MISMATCH");
    JsonNode playFailure = json(payloads.get(1));
    assertThat(playFailure.path("eventType").asText()).isEqualTo("command_result");
    assertThat(playFailure.path("commandType").asText()).isEqualTo("PLAY");
    assertThat(playFailure.path("accepted").asBoolean()).isFalse();
    assertThat(playFailure.path("errorCode").asText()).isEqualTo("LOGIN_REQUIRED");
    assertThat(playFailure.path("outputs").isArray()).isTrue();
  }

  @Test
  void websocketFirstPartyLoginRejectsStalePointerAfterCutover() throws Exception {
    bumpProductionAdmissionPointer(2L, true);

    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver(
            "2", firstPartyClaims("demo", "production", "1", "1", "stale-login"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      payloads = client.responses();
    }

    JsonNode loginFailure = json(payloads.getFirst());
    assertThat(loginFailure.path("eventType").asText()).isEqualTo("command_result");
    assertThat(loginFailure.path("commandType").asText()).isEqualTo("LOGIN");
    assertThat(loginFailure.path("accepted").asBoolean()).isFalse();
    assertThat(loginFailure.path("errorCode").asText()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void websocketFirstPartyReconnectAfterCutoverFailsClosedWithoutLeakingOldGameplayBinding()
      throws Exception {
    try (GameplayWebSocketDriver first =
        openFirstPartyDriver(
            "2", firstPartyClaims("demo", "production", "1", "1", "resume-before-cutover"))) {
      first.send("LOGIN");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      first.send("PLAY demo");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      first.send("LOOK");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "LOOK"), "structured LOOK result");
    }

    bumpProductionAdmissionPointer(2L, true);

    List<String> payloads;
    try (GameplayWebSocketDriver reconnecting =
        openFirstPartyDriver(
            "2", firstPartyClaims("demo", "production", "1", "1", "resume-after-cutover"))) {
      reconnecting.send("LOGIN");
      reconnecting.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      reconnecting.send("LOOK");
      reconnecting.awaitMatching(
          payload -> isStructuredCommand(payload, "LOOK"), "structured LOOK result");
      payloads = reconnecting.responses();
    }

    JsonNode loginFailure =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "LOGIN"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(loginFailure.path("accepted").asBoolean()).isFalse();
    assertThat(loginFailure.path("errorCode").asText()).isEqualTo("CONNECT_SCOPE_MISMATCH");

    JsonNode lookFailure =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "LOOK"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(lookFailure.path("accepted").asBoolean()).isFalse();
    assertThat(lookFailure.path("errorCode").asText()).isEqualTo("LOGIN_REQUIRED");
  }

  @Test
  void websocketFirstPartyRouteChangeOnReusedTransportClearsOldGameplayBeforeRelogin()
      throws Exception {
    try (GameplayWebSocketDriver first =
        openFirstPartyDriver("2", firstPartyClaims("demo", "production", "1", "1", "route-a"))) {
      first.send("LOGIN");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      first.send("PLAY demo");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      first.send("LOOK");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "LOOK"), "structured LOOK result");
    }

    List<String> payloads;
    try (GameplayWebSocketDriver second =
        openFirstPartyDriver("2", firstPartyClaims("sandbox", "production", "2", "1", "route-b"))) {
      second.send("LOGIN");
      second.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      second.send("LOOK");
      second.awaitMatching(
          payload -> isStructuredCommand(payload, "LOOK"), "structured LOOK result");
      second.send("REALMS sandbox");
      second.awaitMatching(
          payload -> isStructuredCommand(payload, "REALMS"), "structured REALMS result");
      payloads = second.responses();
    }

    JsonNode loginSuccess =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "LOGIN"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(loginSuccess.path("accepted").asBoolean()).isTrue();

    JsonNode lookFailure =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "LOOK"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(lookFailure.path("accepted").asBoolean()).isFalse();
    assertThat(lookFailure.path("errorCode").asText()).isEqualTo("PLAY_REQUIRED");

    JsonNode realmsSuccess =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "REALMS"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(realmsSuccess.path("accepted").asBoolean()).isTrue();
    assertThat(realmsSuccess.path("outputs").get(0).path("payload").path("worldSlug").asText())
        .isEqualTo("sandbox");

    assertThat(sessionContextService.findByTenantAndSessionId(22L, 2L))
        .hasValueSatisfying(
            context -> {
              assertThat(context.accountId()).isEqualTo(123L);
              assertThat(context.gameInstanceId()).isZero();
              assertThat(context.characterId()).isZero();
              assertThat(context.bootstrapGameInstanceId()).isEqualTo(2L);
              assertThat(context.worldSlug()).isEqualTo("sandbox");
              assertThat(context.realmSlug()).isEqualTo("production");
              assertThat(context.pointerVersion()).isEqualTo(1L);
              assertThat(context.connectScopeId()).isEqualTo("scope-route-b");
              assertThat(context.connectRequestId()).isEqualTo("connect-req-route-b");
            });
  }

  @Test
  void websocketFirstPartyPlayRejectsCutoverAfterLogin() throws Exception {
    List<String> payloads;
    try (GameplayWebSocketDriver client =
        openFirstPartyDriver("2", firstPartyClaims("demo", "production", "1", "1", "stale-play"))) {
      client.send("LOGIN");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      bumpProductionAdmissionPointer(2L, true);
      client.send("PLAY demo");
      client.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      payloads = client.responses();
    }

    JsonNode loginSuccess = json(payloads.getFirst());
    assertThat(loginSuccess.path("eventType").asText()).isEqualTo("command_result");
    assertThat(loginSuccess.path("commandType").asText()).isEqualTo("LOGIN");
    assertThat(loginSuccess.path("accepted").asBoolean()).isTrue();

    JsonNode playFailure = json(payloads.getLast());
    assertThat(playFailure.path("eventType").asText()).isEqualTo("command_result");
    assertThat(playFailure.path("commandType").asText()).isEqualTo("PLAY");
    assertThat(playFailure.path("accepted").asBoolean()).isFalse();
    assertThat(playFailure.path("errorCode").asText()).isEqualTo("CONNECT_SCOPE_MISMATCH");
  }

  @Test
  void websocketFirstPartySelectorChangeOnReusedTransportClearsOldGameplayBeforeRelogin()
      throws Exception {
    try (GameplayWebSocketDriver first =
        openFirstPartyDriver(
            "2", firstPartyClaims("demo", "production", "1", "1", "scope-selector-a"))) {
      first.send("LOGIN");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      first.send("PLAY demo");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "PLAY"), "structured PLAY result");
      first.send("LOOK");
      first.awaitMatching(
          payload -> isStructuredCommand(payload, "LOOK"), "structured LOOK result");
    }

    List<String> payloads;
    try (GameplayWebSocketDriver second =
        openFirstPartyDriver(
            "2", firstPartyClaims("demo", "production", "1", "1", "scope-selector-b"))) {
      second.send("LOOK");
      second.awaitMatching(
          payload -> isStructuredCommand(payload, "LOOK"), "structured LOOK result");
      second.send("LOGIN");
      second.awaitMatching(
          payload -> isStructuredCommand(payload, "LOGIN"), "structured LOGIN result");
      payloads = second.responses();
    }

    JsonNode lookFailure =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "LOOK"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(lookFailure.path("accepted").asBoolean()).isFalse();
    assertThat(lookFailure.path("errorCode").asText()).isEqualTo("LOGIN_REQUIRED");

    JsonNode loginSuccess =
        payloads.stream()
            .filter(payload -> isStructuredCommand(payload, "LOGIN"))
            .findFirst()
            .map(GameSessionWebSocketHandlerIntegrationTest::json)
            .orElseThrow();
    assertThat(loginSuccess.path("accepted").asBoolean()).isTrue();

    assertThat(sessionContextService.findByTenantAndSessionId(22L, 2L))
        .hasValueSatisfying(
            context -> {
              assertThat(context.accountId()).isEqualTo(123L);
              assertThat(context.gameInstanceId()).isZero();
              assertThat(context.characterId()).isZero();
              assertThat(context.bootstrapGameInstanceId()).isEqualTo(1L);
              assertThat(context.worldSlug()).isEqualTo("demo");
              assertThat(context.realmSlug()).isEqualTo("production");
              assertThat(context.pointerVersion()).isEqualTo(1L);
              assertThat(context.connectScopeId()).isEqualTo("scope-scope-selector-b");
              assertThat(context.connectRequestId()).isEqualTo("connect-req-scope-selector-b");
            });
    assertThat(gameplayPresenceService.findConnectedBySessionId(2L)).isEmpty();
  }

  private GameplayWebSocketDriver openGameplayDriver(String sessionId) {
    return openGameplayDriver(sessionId, java.util.Map.of());
  }

  private GameplayWebSocketDriver openAdmittedGameplayDriver(String sessionId) throws Exception {
    return openAdmittedGameplayDriver(sessionId, java.util.Map.of());
  }

  private GameplayWebSocketDriver openAdmittedGameplayDriver(
      String sessionId, java.util.Map<String, String> extraHeaders) throws Exception {
    return GameplayWebSocketScenarios.openAdmitted(
        ignored -> openGameplayDriver(sessionId, extraHeaders),
        "session-" + sessionId,
        GameplayWebSocketScenarios.Admission.unnamed(
            "demo@example.com", "swordfish", "demo", "Candle-lit Antechamber"));
  }

  private GameplayWebSocketDriver openGameplayDriver(
      String sessionId, java.util.Map<String, String> extraHeaders) {
    java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
    headers.put("X-Game-Instance-Id", "1");
    headers.put("X-Tenant-Id", "22");
    headers.put("X-Firemud-Transport-Session-Id", sessionId);
    extraHeaders.forEach(headers::put);
    return GameplayWebSocketDriver.connect(
        websocketUri(), java.time.Duration.ofSeconds(10), headers);
  }

  private GameplayWebSocketDriver openFirstPartyDriver(
      String transportSessionId, java.util.Map<String, Object> connectClaims) {
    return GameplayWebSocketDriver.connectFirstPartyWeb(
        websocketUri(),
        java.time.Duration.ofSeconds(10),
        transportSessionId,
        "testsecretkeytestsecretkeytest1234",
        connectClaims);
  }

  private java.util.Map<String, Object> firstPartyClaims(
      String worldSlug,
      String realmSlug,
      String gameInstanceId,
      String pointerVersion,
      String suffix) {
    return java.util.Map.of(
        "accountId",
        "123",
        "tenantId",
        "22",
        "worldSlug",
        worldSlug,
        "realmSlug",
        realmSlug,
        "gameInstanceId",
        gameInstanceId,
        "pointerVersion",
        pointerVersion,
        "connectScopeId",
        "scope-" + suffix,
        "connectTokenJti",
        "connect-jti-" + suffix,
        "connectRequestId",
        "connect-req-" + suffix,
        "gatewayRequestId",
        "gateway-req-" + suffix);
  }

  private WebSocketHttpHeaders gameplayHeaders(String sessionId) {
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "1");
    headers.add("X-Tenant-Id", "22");
    headers.add("X-Firemud-Transport-Session-Id", sessionId);
    return headers;
  }

  private URI websocketUri() {
    return URI.create("ws://localhost:" + port + "/ws/game");
  }

  private void performLogoutFlow(String sessionId) throws Exception {
    try (GameplayWebSocketDriver client = openAdmittedGameplayDriver(sessionId)) {
      client.send("LOGOUT");
      GameplayWebSocketDriver.CloseEvent closeEvent = client.awaitClosed();
      assertThat(closeEvent.reason()).isEqualTo("LOGOUT");
    }
  }

  private void bumpProductionAdmissionPointer(
      long newGameInstanceId, boolean requiresCharacterSelection) {
    long expectedPointerVersion =
        gameplayAdmissionPointerAuthorityService.listPointers().stream()
            .filter(
                pointer ->
                    pointer.tenantId() == 22L
                        && "demo".equals(pointer.worldSlug())
                        && "production".equals(pointer.realmSlug()))
            .findFirst()
            .orElseThrow()
            .pointerVersion();
    gameplayAdmissionPointerAuthorityService.upsertPointer(
        new GameplayAdmissionPointerMutation(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            22L,
            newGameInstanceId,
            true,
            true,
            requiresCharacterSelection,
            "SHARED",
            "ALLOW_NEW",
            "integration-test",
            "cutover-proof",
            "req-cutover-" + newGameInstanceId + "-" + expectedPointerVersion,
            expectedPointerVersion,
            "integration-test-prep-" + newGameInstanceId));
  }

  private void resetAdmissionPointers() {
    gameplayAdmissionPointerEventRepository.deleteAllInBatch();
    gameplayAdmissionPointerRepository.deleteAllInBatch();
    gameplayAdmissionPointerAuthorityService.upsertPointer(
        new GameplayAdmissionPointerMutation(
            "demo",
            "Demo World",
            "production",
            "Live Realm",
            22L,
            1L,
            true,
            true,
            false,
            "SHARED",
            "ALLOW_NEW",
            "integration-test",
            "reset-default-demo-pointer",
            "req-reset-demo",
            null,
            null));
    gameplayAdmissionPointerAuthorityService.upsertPointer(
        new GameplayAdmissionPointerMutation(
            "sandbox",
            "Builder Sandbox",
            "production",
            "Live Realm",
            22L,
            2L,
            true,
            true,
            true,
            "SHARED",
            "ALLOW_NEW",
            "integration-test",
            "reset-default-sandbox-pointer",
            "req-reset-sandbox",
            null,
            null));
  }

  private static JsonNode json(String payload) {
    return GameplayStructuredCommandAssertions.parseStructuredResponse(payload);
  }

  private static boolean isStructuredCommand(String payload, String commandType) {
    return GameplayStructuredCommandAssertions.isStructuredCommand(payload, commandType);
  }

  private static boolean containsKind(JsonNode envelope, String kind) {
    return GameplayStructuredCommandAssertions.containsKind(envelope, kind);
  }

  private static boolean matchesContext(
      net.firedevops.firemud.gamesession.service.SessionContext context,
      long tenantId,
      long sessionId,
      long characterId,
      long gameInstanceId,
      String roomInstanceId) {
    return context != null
        && context.tenantId() == tenantId
        && context.sessionId() == sessionId
        && context.characterId() == characterId
        && context.gameInstanceId() == gameInstanceId
        && roomInstanceId.equals(context.roomInstanceId());
  }
}
