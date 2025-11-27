package integration.net.firedevops.firemud.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.GameSessionServiceApplication;
import net.firedevops.firemud.command.text.LookCommandConstants;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import org.junit.jupiter.api.Test;
import org.lognet.springboot.grpc.GRpcServerRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=test",
      "game-session.log-only=true",
      "firemud.database.enabled=false",
      "spring.application.name=game-session-service",
      "grpc.server.port=0",
    })
@ActiveProfiles("test")
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

  @MockBean private GRpcServerRunner grpcServerRunner;

  @MockBean private CommandService commandService;

  @Test
  void websocketCommandIsEnqueuedAndClientGetsAck() throws Exception {
    when(commandService.enqueue("42", "LOOK", false)).thenReturn(CommandEnqueueResult.success());

    StandardWebSocketClient client = new StandardWebSocketClient();
    WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
    headers.add("X-Session-Id", "42");
    AtomicReference<String> responsePayload = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    client
        .doHandshake(
            new TextWebSocketHandler() {
              @Override
              public void afterConnectionEstablished(WebSocketSession session) throws IOException {
                session.sendMessage(new TextMessage("LOOK"));
              }

              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                responsePayload.set(message.getPayload());
                latch.countDown();
              }

              @Override
              public void handleTransportError(WebSocketSession session, Throwable exception) {
                latch.countDown();
                throw new RuntimeException(exception);
              }
            },
            headers,
            URI.create("ws://localhost:" + port + "/ws/game"))
        .get(5, TimeUnit.SECONDS);

    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    verify(commandService).enqueue("42", "LOOK", false);
    assertThat(responsePayload.get()).isEqualTo(LookCommandConstants.LOOK_RESPONSE);
  }
}
