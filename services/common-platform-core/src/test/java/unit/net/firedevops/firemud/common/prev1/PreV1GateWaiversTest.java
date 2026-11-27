package unit.net.firedevops.firemud.common.prev1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.firedevops.firemud.common.prev1.PreV1GateWaivers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PreV1GateWaiversTest {
  @Test
  void disabledByDefault() {
    assertThat(PreV1GateWaivers.from(new MockEnvironment()).enabled()).isFalse();
  }

  @Test
  void enabledOutsideProd() {
    var env = new MockEnvironment().withProperty(PreV1GateWaivers.PROPERTY, "true");
    env.setActiveProfiles("dev");
    assertThat(PreV1GateWaivers.from(env).enabled()).isTrue();
  }

  @Test
  void refusesUnderProdProfile() {
    var env = new MockEnvironment().withProperty(PreV1GateWaivers.PROPERTY, "true");
    env.setActiveProfiles("prod");
    assertThatThrownBy(() -> PreV1GateWaivers.from(env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prod");
  }

  @Test
  void prodWithoutWaiversStarts() {
    var env = new MockEnvironment();
    env.setActiveProfiles("prod");
    assertThat(PreV1GateWaivers.from(env).enabled()).isFalse();
  }
}
