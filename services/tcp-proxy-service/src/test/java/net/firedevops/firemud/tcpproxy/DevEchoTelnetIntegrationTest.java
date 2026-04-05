package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

@SpringBootTest(
    classes = TcpProxyServiceApplication.class,
    webEnvironment = WebEnvironment.DEFINED_PORT)
@ActiveProfiles("dev")
@Import(NoGrpcServerTestConfiguration.class)
class DevEchoTelnetIntegrationTest {
  private static final int WEB_SERVER_PORT = allocatePort();
  private static final int TELNET_SERVER_PORT = allocatePort();

  @Autowired private TelnetServer telnetServer;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> WEB_SERVER_PORT);
    registry.add("GATEWAY_WS_URL", () -> "ws://localhost:" + WEB_SERVER_PORT + "/dev/echo");
    registry.add("TCP_PROXY_PORT", () -> TELNET_SERVER_PORT);
    registry.add("TCP_PROXY_DEV_ISOLATED", () -> true);
    registry.add("TCP_PROXY_DEFAULT_GAME_INSTANCE_ID", () -> "1");
    registry.add("TCP_PROXY_DEFAULT_TENANT_ID", () -> "1");
    registry.add("spring.grpc.server.port", () -> 0);
  }

  @Test
  void telnetClientGetsDevEcho() throws Exception {
    try (Socket socket = new Socket("localhost", telnetServer.getPort());
        PrintWriter writer =
            new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1),
                true);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout(10_000);
      assertThat(reader.readLine()).isEqualTo("OK CONNECTED");
      assertThat(reader.readLine()).isEqualTo("Type WORLDS to list available worlds.");
      assertThat(reader.readLine()).isEqualTo("Type LOGIN <email> <password> to authenticate.");
      assertThat(reader.readLine()).isEqualTo("Type PLAY <world> after LOGIN to enter a world.");
      assertThat(reader.readLine()).isEqualTo("Type HELP for commands.");
      String payload = "hello dev echo";
      writer.println(payload);
      String echoed = reader.readLine();
      assertThat(echoed).isEqualTo(payload);
    }
  }

  private static int allocatePort() {
    try {
      return TestSocketUtils.findAvailableTcpPort();
    } catch (IllegalStateException e) {
      throw new IllegalStateException("Unable to allocate port for Dev Echo test", e);
    }
  }
}
