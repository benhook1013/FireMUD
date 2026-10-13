package unit.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.accountservice.config.AccountJwtSignerBootstrapConfiguration;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccountJwtSignerBootstrapConfigurationTest {
  @Test
  void bootstrapCoordinatorIsNotComposedByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(AccountJwtSignerBootstrapConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(AccountJwtSignerBootstrapCoordinator.class);
            });
  }
}
