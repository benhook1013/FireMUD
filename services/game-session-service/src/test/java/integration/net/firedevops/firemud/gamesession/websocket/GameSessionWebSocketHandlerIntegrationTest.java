package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.command.text.LookTextRenderer;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.testsupport.InMemorySessionContextTestConfiguration;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SuppressWarnings({"removal"})
@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=test",
      "game-session.dev-isolated=false",
      "game-session.require-authenticated-commands=true",
      "firemud.database.enabled=false",
      "spring.data.redis.repositories.enabled=false",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0",
      "firemud.gateway.connect-context.jwt-secret=testsecretkeytestsecretkeytest1234",
    })
@ActiveProfiles("test")
@Import({NoGrpcServerTestConfiguration.class, InMemorySessionContextTestConfiguration.class})
class GameSessionWebSocketHandlerIntegrationTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:h2:mem:game-session-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS saga\\;SET SCHEMA saga");
    registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add("spring.jpa.properties.hibernate.default_schema", () -> "saga");
  }

  @LocalServerPort private int port;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean private AccountClient accountClient;

  @MockitoBean private GameInstanceRepository gameInstanceRepository;

  @MockitoBean private GameLogicClient gameLogicClient;

  @MockitoBean private LookTextRenderer lookTextRenderer;

  @MockitoBean private CommandService commandService;

  @MockitoBean private LookCacheService lookCacheService;

  @MockitoBean private ScreenBufferService screenBufferService;

  @MockitoBean private RedisConnectionFactory redisConnectionFactory;

  @MockitoBean private RedisTemplate<String, Object> redisTemplate;

  @Autowired private SessionContextService sessionContextService;

  @BeforeEach
  void setUp() {
    sessionContextService.deleteBySessionId(22L, 41L);
    sessionContextService.deleteBySessionId(22L, 42L);

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
    when(accountClient.getTenantMembershipForRuntime(
            eq("123"), eq("22"), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            GetTenantMembershipForRuntimeResponse.newBuilder()
                .setAccountId("123")
                .setTenantId("22")
                .setGameplayAdmissionAllowed(true)
                .setMembershipVersion(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(accountClient.getTenantEntitlementsForRuntime(
            eq("22"), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            GetTenantEntitlementsForRuntimeResponse.newBuilder()
                .setTenantId("22")
                .setGameplayAvailable(true)
                .setEntitlementVersion(1L)
                .setTenantBillingSequence(1L)
                .setEvaluatedAt("2026-03-30T00:00:00Z")
                .build());
    when(commandService.enqueue(eq("41"), eq("LOGIN demo@example.com swordfish"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("41"), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("42"), eq("LOGIN demo@example.com swordfish"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("42"), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(gameLogicClient.resolveLook(eq("22"), eq("41"), eq("123"), eq("1021")))
        .thenReturn(lookResult);
    when(gameLogicClient.resolveLook(eq("22"), eq("42"), eq("123"), eq("1021")))
        .thenReturn(lookResult);
    when(lookTextRenderer.render(eq(lookResult))).thenReturn("Login Hall text");
    when(screenBufferService.get(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(Optional.empty());
    GameInstance instance = new GameInstance();
    instance.setId(41L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    when(gameInstanceRepository.findById(41L)).thenReturn(Optional.of(instance));
    instance = new GameInstance();
    instance.setId(42L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    when(gameInstanceRepository.findById(42L)).thenReturn(Optional.of(instance));
  }

  @Test
  void websocketLoginThenLookUsesAuthenticatedPath() throws Exception {
    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "41");
    List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(3);

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOGIN demo@example.com swordfish"));
                session.sendMessage(new TextMessage("PLAY demo"));
                session.sendMessage(new TextMessage("LOOK"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                payloads.add(message.getPayload());
                latch.countDown();
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable exception) {
                latch.countDown();
                throw new RuntimeException(exception);
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
    assertThat(payloads.get(0)).startsWith("OK LOGIN");
    assertThat(payloads.get(1)).startsWith("OK PLAY");
    assertThat(payloads.get(2)).startsWith("OK LOOK");
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 41L))
        .hasValueSatisfying(
            context -> {
              assertThat(context.gameInstanceId()).isEqualTo(1L);
              assertThat(context.characterId()).isEqualTo(123L);
              assertThat(context.roomInstanceId()).isNotBlank();
            });

    verify(commandService).enqueue("41", "LOGIN demo@example.com swordfish", false);
    verify(commandService).enqueue("41", "LOOK", false);
    verify(gameLogicClient).resolveLook("22", "41", "123", "1021");
    verify(lookCacheService)
        .cache(
            eq(22L), eq(1L), eq("1021"), eq("Login Hall text"), eq("OK LOOK\nLogin Hall text\n\n"));
  }

  @Test
  void websocketMoveReturnsDestinationLookAndUpdatesSessionContext() throws Exception {
    LookResult destinationLook =
        LookResult.newBuilder()
            .setRoomInstance(RoomInstanceRef.newBuilder().setRoomInstanceId("2045").build())
            .setRoomName("North Hall")
            .setShortDescription("A northern hall")
            .setLongDescription("A northern hall used for movement verification.")
            .build();
    when(gameLogicClient.resolveMove(eq("22"), eq("42"), eq("123"), eq("1021"), eq("north")))
        .thenReturn(
            net.firedevops.firemud.gamelogic.v1.MoveResult.newBuilder()
                .setSuccess(true)
                .setDestinationLook(destinationLook)
                .build());
    when(lookTextRenderer.render(eq(destinationLook))).thenReturn("North Hall text");

    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");
    List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(3);

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOGIN demo@example.com swordfish"));
                session.sendMessage(new TextMessage("PLAY demo"));
                session.sendMessage(new TextMessage("north"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                payloads.add(message.getPayload());
                latch.countDown();
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable exception) {
                latch.countDown();
                throw new RuntimeException(exception);
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(3);
    assertThat(payloads.get(0)).startsWith("OK LOGIN");
    assertThat(payloads.get(1)).startsWith("OK PLAY");
    assertThat(payloads.get(2)).isEqualTo("OK LOOK\nNorth Hall text\n\n");
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 42L))
        .hasValueSatisfying(context -> assertThat(context.roomInstanceId()).isEqualTo("2045"));

    verify(gameLogicClient).resolveMove("22", "42", "123", "1021", "north");
    verify(lookCacheService)
        .cache(
            eq(22L), eq(1L), eq("2045"), eq("North Hall text"), eq("OK LOOK\nNorth Hall text\n\n"));
  }

  @Test
  void websocketFirstPartyLoginConsumesVerifiedConnectContext() throws Exception {
    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Firemud-Connection-Mode", "first_party_web");
    headers.add(
        "X-Firemud-Connect-Context",
        new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L)
            .generateToken(
                "123",
                java.util.Map.of(
                    "tenantId", "22",
                    "gameInstanceId", "41",
                    "connectTokenJti", "connect-jti-1",
                    "gatewayRequestId", "gateway-req-1")));
    List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOGIN"));
                session.sendMessage(new TextMessage("PLAY demo"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                payloads.add(message.getPayload());
                latch.countDown();
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(payloads.get(0)).startsWith("OK LOGIN");
    assertThat(payloads.get(1)).startsWith("OK PLAY");
    verify(accountClient)
        .getTenantMembershipForRuntime(
            eq("123"), eq("22"), org.mockito.ArgumentMatchers.anyString());
    verify(accountClient)
        .getTenantEntitlementsForRuntime(eq("22"), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void websocketFirstPartyReconnectReplaysBufferedScreenAndFreshLookAfterPlay() throws Exception {
    when(screenBufferService.get(eq(22L), eq(1L), eq(123L)))
        .thenReturn(
            Optional.of(
                new ScreenBufferService.BufferedScreen(
                    "Recent combat line\nSecond recent line\n", 2, 32)));

    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Firemud-Connection-Mode", "first_party_web");
    headers.add(
        "X-Firemud-Connect-Context",
        new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L)
            .generateToken(
                "123",
                java.util.Map.of(
                    "tenantId", "22",
                    "gameInstanceId", "41",
                    "connectTokenJti", "connect-jti-2",
                    "gatewayRequestId", "gateway-req-2")));
    List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(4);

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOGIN"));
                session.sendMessage(new TextMessage("PLAY demo"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                payloads.add(message.getPayload());
                latch.countDown();
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(payloads.get(0)).startsWith("OK LOGIN");
    assertThat(payloads.get(1)).startsWith("OK PLAY");
    assertThat(payloads.get(2)).contains("Recent combat line");
    assertThat(payloads.get(3)).startsWith("OK LOOK");
  }

  @Test
  void websocketFirstPartyInvalidConnectContextClosesImmediately() throws Exception {
    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Firemud-Connection-Mode", "first_party_web");
    headers.add("X-Firemud-Connect-Context", "not-a-valid-context");
    CountDownLatch latch = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<org.springframework.web.socket.CloseStatus> close =
        new java.util.concurrent.atomic.AtomicReference<>();

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionClosed(
                  WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
                close.set(status);
                latch.countDown();
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(close.get()).isNotNull();
    assertThat(close.get().getCode()).isEqualTo(1008);
    assertThat(close.get().getReason()).isEqualTo("CONNECT_CONTEXT_INVALID");
  }

  @Test
  void websocketFirstPartyPlayRejectsScopeMismatch() throws Exception {
    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Firemud-Connection-Mode", "first_party_web");
    headers.add(
        "X-Firemud-Connect-Context",
        new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L)
            .generateToken(
                "123",
                java.util.Map.of(
                    "tenantId", "22",
                    "gameInstanceId", "41",
                    "connectTokenJti", "connect-jti-2",
                    "gatewayRequestId", "gateway-req-2")));
    List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOGIN"));
                session.sendMessage(new TextMessage("PLAY sandbox Sora"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                payloads.add(message.getPayload());
                latch.countDown();
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(payloads.get(0)).startsWith("OK LOGIN");
    assertThat(payloads.get(1)).startsWith("ERROR CONNECT_SCOPE_MISMATCH");
  }
}
