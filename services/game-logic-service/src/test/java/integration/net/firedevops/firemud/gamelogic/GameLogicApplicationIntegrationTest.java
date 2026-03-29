package net.firedevops.firemud.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameLogicServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0"
    })
@Import(NoGrpcServerTestConfiguration.class)
class GameLogicApplicationIntegrationTest {

  @LocalServerPort private int port;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }
}
