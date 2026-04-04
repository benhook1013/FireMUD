package net.firedevops.firemud.common.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/** Publishes runtime identity through the actuator info surface. */
public class RuntimeIdentityInfoContributor implements InfoContributor {
  private final RuntimeIdentity runtimeIdentity;

  public RuntimeIdentityInfoContributor(RuntimeIdentity runtimeIdentity) {
    this.runtimeIdentity = runtimeIdentity;
  }

  @Override
  public void contribute(Info.Builder builder) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("service", runtimeIdentity.service());
    details.put("serviceInstanceId", runtimeIdentity.serviceInstanceId());
    putIfPresent(details, "hostname", runtimeIdentity.hostname());
    details.put("bootedAt", runtimeIdentity.bootedAt().toString());
    putIfPresent(details, "buildVersion", runtimeIdentity.buildVersion());
    putIfPresent(details, "buildSha", runtimeIdentity.buildSha());
    putIfPresent(details, "imageTag", runtimeIdentity.imageTag());
    builder.withDetail("runtime", details);
  }

  private static void putIfPresent(Map<String, Object> details, String key, String value) {
    if (value != null && !value.isBlank()) {
      details.put(key, value);
    }
  }
}
