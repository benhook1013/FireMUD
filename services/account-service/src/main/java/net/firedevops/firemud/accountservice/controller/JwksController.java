package net.firedevops.firemud.accountservice.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

  public JwksController(ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
  }

  @GetMapping(value = "/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> jwks() throws IOException {
    Resource resource = resourceLoader.getResource("classpath:jwks.json");
    byte[] bytes = resource.getInputStream().readAllBytes();
    return ResponseEntity.ok(new String(bytes, StandardCharsets.UTF_8));
  }
}
