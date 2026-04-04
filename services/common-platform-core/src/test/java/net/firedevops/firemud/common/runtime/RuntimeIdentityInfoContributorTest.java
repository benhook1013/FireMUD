package net.firedevops.firemud.common.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

class RuntimeIdentityInfoContributorTest {

  @Test
  void contributesRuntimeIdentityDetails() {
    RuntimeIdentity runtimeIdentity =
        new RuntimeIdentity(
            "game-session-service",
            "game-session-123",
            "host-a",
            Instant.parse("2026-04-02T10:15:30Z"),
            "1.2.3",
            "abc123",
            "firemud:1.2.3");

    Info.Builder builder = new Info.Builder();
    new RuntimeIdentityInfoContributor(runtimeIdentity).contribute(builder);
    Map<String, Object> runtime = castRuntime(builder.build().getDetails().get("runtime"));

    assertThat(runtime)
        .containsEntry("service", "game-session-service")
        .containsEntry("serviceInstanceId", "game-session-123")
        .containsEntry("hostname", "host-a")
        .containsEntry("bootedAt", "2026-04-02T10:15:30Z")
        .containsEntry("buildVersion", "1.2.3")
        .containsEntry("buildSha", "abc123")
        .containsEntry("imageTag", "firemud:1.2.3");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castRuntime(Object runtime) {
    return (Map<String, Object>) runtime;
  }
}
