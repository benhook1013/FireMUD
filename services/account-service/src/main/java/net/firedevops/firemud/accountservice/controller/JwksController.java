package net.firedevops.firemud.accountservice.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/.well-known")
public class JwksController {
  private final ResourceLoader resourceLoader;
  private final String jwksPath;

  public JwksController(
      ResourceLoader resourceLoader, @Value("${firemud.auth.jwks-path:}") String jwksPath) {
    this.resourceLoader = resourceLoader;
    this.jwksPath = jwksPath;
  }

  @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> jwks() throws IOException {
    if (jwksPath != null && !jwksPath.isBlank()) {
      return ResponseEntity.ok(Files.readString(Path.of(jwksPath), StandardCharsets.UTF_8));
    }
    Resource resource = resourceLoader.getResource("classpath:jwks.json");
    byte[] bytes = resource.getInputStream().readAllBytes();
    return ResponseEntity.ok(new String(bytes, StandardCharsets.UTF_8));
  }
}
