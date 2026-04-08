package net.firedevops.firemud.worldmanagement.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthWiringTest {
  @Test
  void authInfrastructureIsPresent() {
    assertTrue(
        Files.exists(
            Path.of("src/main/java/net/firedevops/firemud/worldmanagement/config/WebConfig.java")));
    assertTrue(
        Files.exists(
            Path.of(
                "src/main/java/net/firedevops/firemud/worldmanagement/config/GrpcConfig.java")));
    assertTrue(
        Files.exists(
            Path.of(
                "src/main/java/net/firedevops/firemud/worldmanagement/security/JwtAuthInterceptor.java")));
  }
}
