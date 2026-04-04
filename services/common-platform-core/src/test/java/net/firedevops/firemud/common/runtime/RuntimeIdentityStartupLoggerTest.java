package net.firedevops.firemud.common.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class RuntimeIdentityStartupLoggerTest {

  @Test
  void emitsStructuredStartupLog(CapturedOutput output) {
    RuntimeIdentity runtimeIdentity =
        new RuntimeIdentity(
            "spring-cloud-gateway",
            "gateway-1",
            "host-a",
            Instant.parse("2026-04-02T10:15:30Z"),
            "1.2.3",
            "abc123",
            "firemud:1.2.3");
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("prod");

    new RuntimeIdentityStartupLogger(runtimeIdentity, environment).onApplicationReady();

    assertThat(output)
        .contains("Service startup complete")
        .contains("service=spring-cloud-gateway")
        .contains("serviceInstanceId=gateway-1")
        .contains("profiles=prod")
        .contains("buildVersion=1.2.3")
        .contains("buildSha=abc123");
  }
}
