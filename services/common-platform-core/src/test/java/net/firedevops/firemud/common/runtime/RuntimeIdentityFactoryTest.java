package net.firedevops.firemud.common.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeIdentityFactoryTest {

  private final RuntimeIdentityFactory factory = new RuntimeIdentityFactory();

  @Test
  void prefersPodIdentityOverHostname() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("POD_NAME", "game-session-abc123")
            .withProperty("HOSTNAME", "node-1");

    RuntimeIdentity identity = factory.create(environment, "game-session-service", null, null);

    assertThat(identity.service()).isEqualTo("game-session-service");
    assertThat(identity.serviceInstanceId()).isEqualTo("game-session-abc123");
    assertThat(identity.hostname()).isEqualTo("node-1");
    assertThat(identity.bootedAt()).isNotNull();
  }

  @Test
  void fallsBackToGeneratedIdentityWhenRuntimeDoesNotProvideOne() {
    RuntimeIdentity identity =
        factory.create(new MockEnvironment(), "tcp-proxy-service", null, null);

    assertThat(identity.service()).isEqualTo("tcp-proxy-service");
    assertThat(identity.serviceInstanceId()).isNotBlank();
    assertThat(identity.bootedAt()).isNotNull();
  }
}
