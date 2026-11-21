package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayStandIns;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class PreV1CanonicalPlayConfigurationTest {
  private static final String COORDINATION =
      "firemud.game-session.initial-gameplay-login.protected-sources.coordination.";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PreV1CanonicalPlayConfiguration.class)
          .withBean(DSLContext.class, () -> mock(DSLContext.class))
          .withPropertyValues(
              COORDINATION + "uri=redis://gamesession_coord_app:secret@redis-coord:6379/0",
              COORDINATION + "command-timeout-millis=2000",
              "firemud.grpc.workload-namespace=test");

  @Test
  void absentWithoutTheCanonicalPlaySwitchOrTheWaivers() {
    runner.run(context -> assertThat(context).doesNotHaveBean(PreV1CanonicalPlayStandIns.class));
    runner
        .withPropertyValues(PreV1CanonicalPlayConfiguration.PROPERTY + "=true")
        .run(context -> assertThat(context).doesNotHaveBean(PreV1CanonicalPlayStandIns.class));
    runner
        .withPropertyValues("firemud.pre-v1.gate-waivers.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(PreV1CanonicalPlayStandIns.class));
  }

  @Test
  void composesUnderBothSwitchesWithoutConnecting() {
    runner
        .withPropertyValues(
            PreV1CanonicalPlayConfiguration.PROPERTY + "=true",
            "firemud.pre-v1.gate-waivers.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(PreV1CanonicalPlayStandIns.class));
  }

  @Test
  void prodProfileFailsStartup() {
    runner
        .withPropertyValues(
            PreV1CanonicalPlayConfiguration.PROPERTY + "=true",
            "firemud.pre-v1.gate-waivers.enabled=true",
            "spring.profiles.active=prod")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void requireWaiversRefusesOffAndProd() {
    assertThatThrownBy(() -> PreV1CanonicalPlayConfiguration.requireWaivers(new MockEnvironment()))
        .isInstanceOf(IllegalStateException.class);
    var prod = new MockEnvironment().withProperty("firemud.pre-v1.gate-waivers.enabled", "true");
    prod.setActiveProfiles("prod");
    assertThatThrownBy(() -> PreV1CanonicalPlayConfiguration.requireWaivers(prod))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("prod");
  }
}
