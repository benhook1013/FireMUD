package net.firedevops.firemud.accountservice.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class JwksControllerTest {
  @Test
  void servesMountedJwksWhenPathIsConfigured() throws Exception {
    Path jwksFile = Files.createTempFile("firemud-jwks", ".json");
    Files.writeString(jwksFile, "{\"keys\":[{\"kid\":\"mounted\"}]}");
    JwksController controller =
        new JwksController(new DefaultResourceLoader(), jwksFile.toString());

    var response = controller.jwks();

    assertThat(response.getBody()).isEqualTo("{\"keys\":[{\"kid\":\"mounted\"}]}");
  }
}
