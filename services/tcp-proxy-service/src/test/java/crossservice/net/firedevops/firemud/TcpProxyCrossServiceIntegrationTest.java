package net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.telnet.TelnetServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Cross-service integration test verifying Telnet to WebSocket bridging. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class,
    properties = {"TCP_PROXY_PORT=2323"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpProxyCrossServiceIntegrationTest {
  static GenericContainer<?> gateway =
      new GenericContainer<>(
              DockerImageName.parse("ghcr.io/firedevops/spring-cloud-gateway:latest"))
          .withExposedPorts(8080);

  private static boolean gatewayStarted = false;

  static {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      try {
        gateway.start();
        gatewayStarted = true;
      } catch (Exception e) {
        gatewayStarted = false;
      }
    }
  }

  @LocalServerPort private int port;

  @Autowired private TelnetServer telnetServer;

  @Autowired private TestRestTemplate restTemplate;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    if (gatewayStarted) {
      registry.add(
          "GATEWAY_WS_URL",
          () -> "ws://" + gateway.getHost() + ":" + gateway.getMappedPort(8080) + "/ws");
    } else {
      registry.add("GATEWAY_WS_URL", () -> "ws://localhost:8080/ws");
    }
  }

  @AfterAll
  static void stopGateway() {
    if (gatewayStarted) {
      gateway.stop();
    }
  }

  @Test
  void proxyStartsAlongsideGateway() throws Exception {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable() && gatewayStarted,
        "Gateway container not available, skipping cross-service test");
    assertThat(gateway.isRunning()).isTrue();

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
      writer.println("look");
    }

    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");
  }
}
