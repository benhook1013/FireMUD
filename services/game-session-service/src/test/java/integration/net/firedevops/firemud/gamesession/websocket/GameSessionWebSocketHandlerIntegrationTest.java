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
import net.firedevops.firemud.cache.LookCacheService;
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

  @MockitoBean private RedisConnectionFactory redisConnectionFactory;

  @MockitoBean private RedisTemplate<String, Object> redisTemplate;

  @Autowired private SessionContextService sessionContextService;

  @BeforeEach
  void setUp() {
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
    when(commandService.enqueue(eq("42"), eq("LOGIN demo@example.com swordfish"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(commandService.enqueue(eq("42"), eq("LOOK"), eq(false)))
        .thenReturn(CommandEnqueueResult.success());
    when(gameLogicClient.resolveLook(eq("22"), eq("42"), eq("123"), eq("1021")))
        .thenReturn(lookResult);
    when(lookTextRenderer.render(eq(lookResult))).thenReturn("Login Hall text");
    GameInstance instance = new GameInstance();
    instance.setId(42L);
    instance.setTenantId(22L);
    instance.setOwnerAccountId(123L);
    when(gameInstanceRepository.findById(42L)).thenReturn(Optional.of(instance));
  }

  @Test
  void websocketLoginThenLookUsesAuthenticatedPath() throws Exception {
    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "42");
    List<String> payloads = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOGIN demo@example.com swordfish"));
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

    assertThat(payloads).hasSizeGreaterThanOrEqualTo(2);
    assertThat(payloads.get(0)).startsWith("OK LOGIN");
    assertThat(payloads.get(1)).startsWith("OK LOOK");
    assertThat(payloads.get(1)).contains("Login Hall text");
    assertThat(sessionContextService.findByTenantAndSessionId(22L, 42L)).isPresent();

    verify(commandService).enqueue("42", "LOGIN demo@example.com swordfish", false);
    verify(commandService).enqueue("42", "LOOK", false);
    verify(gameLogicClient).resolveLook("22", "42", "123", "1021");
    verify(lookCacheService)
        .cache(
            eq(22L),
            eq(42L),
            eq("1021"),
            eq("Login Hall text"),
            eq("OK LOOK\nLogin Hall text\n\n"));
  }
}
