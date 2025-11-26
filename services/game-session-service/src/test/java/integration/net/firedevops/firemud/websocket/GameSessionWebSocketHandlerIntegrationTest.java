package integration.net.firedevops.firemud.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.GameSessionServiceApplication;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.service.CommandService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=test",
      "game-session.log-only=true",
      "firemud.database.enabled=false"
    })
@ActiveProfiles("test")
class GameSessionWebSocketHandlerIntegrationTest {

  @LocalServerPort private int port;

  @MockBean private CommandService commandService;

  @Test
  void websocketCommandIsEnqueuedAndClientGetsAck() {
    when(commandService.enqueue("42", "LOOK", false)).thenReturn(CommandEnqueueResult.success());

    ReactorNettyWebSocketClient client =
        new ReactorNettyWebSocketClient(
            HttpClient.create().headers(headers -> headers.add("X-Session-Id", "42")));
    AtomicReference<String> responsePayload = new AtomicReference<>();

    client
        .execute(
            URI.create("ws://localhost:" + port + "/ws/game"),
            session ->
                session
                    .send(Mono.just(session.textMessage("LOOK")))
                    .thenMany(
                        session
                            .receive()
                            .take(1)
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(responsePayload::set))
                    .then())
        .block(Duration.ofSeconds(5));

    verify(commandService).enqueue("42", "LOOK", false);
    assertThat(responsePayload.get()).isEqualTo("OK LOOK");
  }
}
