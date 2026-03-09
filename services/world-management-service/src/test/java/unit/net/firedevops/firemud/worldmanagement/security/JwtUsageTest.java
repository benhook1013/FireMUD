package net.firedevops.firemud.worldmanagement.security;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class JwtUsageTest {
  @Test
  void noJwtReferencesInMainSources() throws IOException {
    try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
      paths
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              p -> {
                try {
                  String content = Files.readString(p, StandardCharsets.UTF_8);
                  assertFalse(content.contains("JwtUtil"), p + " should not reference JwtUtil");
                  assertFalse(
                      content.contains("SessionContext"),
                      p + " should not reference SessionContext");
                  assertFalse(
                      content.contains("Authorization"),
                      p + " should not mention Authorization header");
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }
}
