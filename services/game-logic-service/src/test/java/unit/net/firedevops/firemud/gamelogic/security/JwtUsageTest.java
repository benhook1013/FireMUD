package net.firedevops.firemud.gamelogic.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class JwtUsageTest {
  @Test
  void detectsAuthorizationHeaderReferenceForms() {
    assertTrue(containsAuthorizationHeaderReference("Metadata.Key.of(\"Authorization\", ...)"));
    assertTrue(containsAuthorizationHeaderReference("request.header(HttpHeaders.AUTHORIZATION)"));
    assertTrue(containsAuthorizationHeaderReference("AUTHORIZATION_HEADER"));
  }

  @Test
  void authorizationExceptionNamesAreNotHeaderReferences() {
    assertFalse(
        containsAuthorizationHeaderReference(
            "throw new AdminAuthorizationException(\"publication read denied\")"));
  }

  @Test
  void noJwtReferencesInMainSources() throws IOException {
    try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
      paths
          .filter(p -> p.toString().endsWith(".java"))
          .filter(
              p ->
                  !p.endsWith(
                      Path.of(
                          "net",
                          "firedevops",
                          "firemud",
                          "gamelogic",
                          "config",
                          "InternalGrpcClientAuthConfig.java")))
          .forEach(
              p -> {
                try {
                  String content = Files.readString(p, StandardCharsets.UTF_8);
                  assertFalse(content.contains("JwtUtil"), p + " should not reference JwtUtil");
                  assertFalse(
                      content.contains("SessionContext"),
                      p + " should not reference SessionContext");
                  assertFalse(
                      containsAuthorizationHeaderReference(content),
                      p + " should not reference the Authorization HTTP header");
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  private static boolean containsAuthorizationHeaderReference(String content) {
    return content.contains("\"Authorization\"")
        || content.contains("\"authorization\"")
        || content.contains("HttpHeaders.AUTHORIZATION")
        || content.contains("AUTHORIZATION_HEADER");
  }
}
