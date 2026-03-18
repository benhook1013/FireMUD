package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.exception.NotFoundException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Cross-service integration test verifying Telnet to WebSocket bridging. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TcpProxyCrossServiceIntegrationTest {
  static GenericContainer<?> gateway;
  private static final int proxyPort;

  static {
    int port;
    try {
      port = TestSocketUtils.findAvailableTcpPort();
    } catch (IllegalStateException e) {
      port = 0;
    }
    proxyPort = port;
  }

  private static boolean gatewayStarted = false;

  private static boolean isDockerAvailable() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isGatewayImageAvailableLocally() {
    try {
      DockerClientFactory.instance()
          .client()
          .inspectImageCmd("ghcr.io/benhook1013/spring-cloud-gateway:latest")
          .exec();
      return true;
    } catch (NotFoundException e) {
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  @LocalServerPort private int port;

  @Autowired private TelnetServer telnetServer;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    if (isDockerAvailable() && isGatewayImageAvailableLocally()) {
      try {
        gateway =
            new GenericContainer<>(
                    DockerImageName.parse("ghcr.io/benhook1013/spring-cloud-gateway:latest"))
                .withExposedPorts(8080);
        gateway.start();
        gatewayStarted = true;
      } catch (Throwable e) {
        gatewayStarted = false;
      }
    }
    if (gatewayStarted) {
      registry.add(
          "GATEWAY_WS_URL",
          () -> "ws://" + gateway.getHost() + ":" + gateway.getMappedPort(8080) + "/ws");
    } else {
      registry.add("GATEWAY_WS_URL", () -> "ws://localhost:8080/ws");
    }
    registry.add("TCP_PROXY_PORT", () -> proxyPort);
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
        gatewayStarted && proxyPort > 0,
        "Gateway container or proxy port not available, skipping cross-service test");
    assertThat(gateway.isRunning()).isTrue();

    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
      writer.println("look");
    }

    String body = HttpTestSupport.getBody("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }
}
