package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceController;
import net.firedevops.firemud.accountservice.controller.AccountHostingFirstInvoiceWebhookController;
import net.firedevops.firemud.accountservice.service.impl.AccountControlUiAuthenticationGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class AccountCheckoutReceivingConfigurationTest {
  @TempDir Path keys;

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountCheckoutReceivingConfiguration.class);

  @Test
  void defaultAndUnqualifiedSelectionRegisterNoReceivingOrSourceGraph() {
    context.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(AccountHostingFirstInvoiceController.class);
          assertThat(ctx).doesNotHaveBean(AccountHostingFirstInvoiceWebhookController.class);
          assertThat(ctx).doesNotHaveBean(AccountControlUiAuthenticationGrpcService.class);
          assertThat(ctx).doesNotHaveBean(AccountJwtJwksTrustedSource.class);
          assertThat(ctx).doesNotHaveBean(AccountControlUiActorService.class);
        });
    context
        .withPropertyValues("firemud.account.checkout-receiving.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(AccountControlUiAuthenticationGrpcService.class);
            });
  }

  @Test
  void isolatedModeWithoutDeliveredInputsRefusesStartup() {
    context
        .withPropertyValues(
            "spring.profiles.active=isolated-checkout-receiving",
            "firemud.account.checkout-receiving.enabled=true")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void productionCannotBeCombinedWithIsolatedReceivingAndCustodyIsSeparate() {
    var environment = new MockEnvironment();
    environment.setActiveProfiles("prod", "isolated-checkout-receiving");
    assertThatThrownBy(
            () ->
                new AccountCheckoutReceivingConfiguration()
                    .accountCheckoutReceivingProperties(environment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountCheckoutReceivingProperties(
                    "test",
                    keys.resolve("private"),
                    keys.resolve("public"),
                    keys.resolve("private/custody"),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
