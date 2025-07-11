package net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.telnet.TelnetServer;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Cross-service integration test verifying Telnet to WebSocket bridging. */
@Disabled("Cross-service environment not configured")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class,
    properties = {"TCP_PROXY_PORT=2323"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpProxyCrossServiceIntegrationTest {
  private static MockWebServer mockGateway;

  @Autowired private TelnetServer telnetServer;

  @BeforeAll
  static void setup() throws Exception {
    mockGateway = new MockWebServer();
    mockGateway.start();
  }

  @AfterAll
  static void tearDown() throws Exception {
    mockGateway.shutdown();
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    String wsUrl = "ws://" + mockGateway.getHostName() + ":" + mockGateway.getPort() + "/ws";
    registry.add("GATEWAY_WS_URL", () -> wsUrl);
  }

  @Test
  void telnetMessagesAreForwardedToGateway() throws Exception {
    LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    MockResponse response =
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onMessage(okhttp3.WebSocket ws, String text) {
                    messages.offer(text);
                  }
                });
    mockGateway.enqueue(response);

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
      writer.println("look");
      // Wait for the proxy to forward the message to the mock gateway
      String forwarded = messages.poll(5, TimeUnit.SECONDS);
      assertThat(forwarded).isEqualTo("look");
    }
  }
}
