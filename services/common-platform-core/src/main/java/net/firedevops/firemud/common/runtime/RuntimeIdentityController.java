package net.firedevops.firemud.common.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lightweight runtime identity endpoint for operator and debug use. */
@RestController
@RequestMapping("/actuator/runtime")
public class RuntimeIdentityController {
  private final RuntimeIdentity runtimeIdentity;

  public RuntimeIdentityController(RuntimeIdentity runtimeIdentity) {
    this.runtimeIdentity = runtimeIdentity;
  }

  @GetMapping
  public Map<String, Object> runtime() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("service", runtimeIdentity.service());
    body.put("serviceInstanceId", runtimeIdentity.serviceInstanceId());
    putIfPresent(body, "hostname", runtimeIdentity.hostname());
    body.put("bootedAt", runtimeIdentity.bootedAt().toString());
    putIfPresent(body, "buildVersion", runtimeIdentity.buildVersion());
    putIfPresent(body, "buildSha", runtimeIdentity.buildSha());
    putIfPresent(body, "imageTag", runtimeIdentity.imageTag());
    return body;
  }

  private static void putIfPresent(Map<String, Object> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }
}
