package net.firedevops.firemud.tcpproxy.telnet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.firedevops.firemud.tcpproxy.TcpProxyServiceApplication;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class,
    properties = {"TCP_PROXY_PORT=0", "GATEWAY_WS_URL=ws://127.0.0.1:9/ws/game"})
@Import(NoGrpcServerTestConfiguration.class)
class TelnetReadinessAdmissionIntegrationTest {

  @Autowired private TelnetServer telnetServer;

  @Test
  void unreadyGameplayPathRejectsNewTelnetConnectionWithExplicitDisconnect() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", telnetServer.getPort());
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))) {
      socket.setSoTimeout(5_000);

      assertThat(reader.readLine())
          .isEqualTo("DISCONNECT startup_unavailable Gameplay path starting; please reconnect");
    }
  }
}
