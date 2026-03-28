package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SuppressWarnings({"removal"})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "game-session.dev-isolated=true",
      "game-session.require-authenticated-commands=false",
      "firemud.database.enabled=false",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0"
    })
@Import(NoGrpcServerTestConfiguration.class)
class GameSessionLoginIntegrationTest {
  @LocalServerPort private int port;

  @MockitoBean private AccountClient accountClient;
  @MockitoBean private GameInstanceRepository gameInstanceRepository;
  @MockitoBean private SessionContextService sessionContextService;
  @MockitoBean private SessionAuthenticationService sessionAuthenticationService;
  @MockitoBean private CommandService commandService;

  @BeforeEach
  void setUp() {
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder().setAuthToken("stub-token").setAccountId("7").build());
    when(commandService.enqueue(anyString(), anyString(), anyBoolean()))
        .thenReturn(CommandEnqueueResult.success());
    GameInstance instance = new GameInstance();
    instance.setId(1L);
    instance.setTenantId(42L);
    instance.setOwnerAccountId(7L);
    when(gameInstanceRepository.findById(anyLong())).thenReturn(Optional.of(instance));
    when(sessionAuthenticationService.isAuthenticated(anyString())).thenReturn(true);
  }

  @Test
  void loginCallsAccountServiceAndReturnsOk() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    List<String> payloads = new CopyOnWriteArrayList<>();

    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Game-Instance-Id", "1");

    var future =
        client.execute(
            new TextWebSocketHandler() {
              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                payloads.add(message.getPayload());
                latch.countDown();
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"));

    WebSocketSession session = future.get(5, TimeUnit.SECONDS);
    session.sendMessage(new TextMessage("LOGIN demo@example.com swordfish"));
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    session.close();

    assertThat(payloads).anyMatch(s -> s.startsWith("OK LOGIN"));

    ArgumentCaptor<String> tenantCaptor = ArgumentCaptor.forClass(String.class);
    verify(accountClient)
        .authenticate(tenantCaptor.capture(), eq("demo@example.com"), eq("swordfish"), eq(""));
    assertThat(tenantCaptor.getValue()).isEqualTo("42");
  }
}
