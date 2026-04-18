package net.firedevops.firemud.entitymanagement.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthWiringTest {
  @Test
  void authInfrastructureIsPresent() {
    assertTrue(
        Files.exists(
            Path.of(
                "src/main/java/net/firedevops/firemud/entitymanagement/config/WebConfig.java")));
    assertTrue(Files.exists(Path.of("src/main/resources/application.yml")));
    assertTrue(
        Files.exists(
            Path.of(
                "src/main/java/net/firedevops/firemud/entitymanagement/security/JwtAuthInterceptor.java")));
  }
}
