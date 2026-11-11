package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
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
  void productionProfilesNeverRegisterTheIsolatedAuthenticationReceiver() {
    for (String profile : java.util.List.of("prod", "production")) {
      context
          .withPropertyValues(
              "spring.profiles.active=" + profile,
              "firemud.account.checkout-receiving.enabled=true")
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).doesNotHaveBean(AccountControlUiAuthenticationGrpcService.class);
              });
      context
          .withPropertyValues(
              "spring.profiles.active=" + profile + ",isolated-checkout-receiving",
              "firemud.account.checkout-receiving.enabled=true")
          .run(ctx -> assertThat(ctx).hasFailed());
    }
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
  void physicalCustodyRootsMustResolveAndRemainDisjoint() throws Exception {
    Path privateRoot = Files.createDirectory(keys.resolve("private"));
    Path publicRoot = Files.createDirectory(keys.resolve("public"));
    Path custody = Files.createDirectory(keys.resolve("custody"));
    assertThat(
            new AccountCheckoutReceivingProperties("test", privateRoot, publicRoot, custody, 0)
                .controlUiKeyringRoot())
        .isEqualTo(custody.toRealPath());
    for (Path signer : java.util.List.of(privateRoot, publicRoot)) {
      Path child = Files.createDirectory(signer.resolve("child"));
      for (Path overlap : java.util.List.of(signer, child, keys)) {
        assertThatThrownBy(
                () ->
                    new AccountCheckoutReceivingProperties(
                        "test", privateRoot, publicRoot, overlap, 0))
            .isInstanceOf(IllegalArgumentException.class);
      }
      Path alias = Files.createSymbolicLink(keys.resolve(signer.getFileName() + "-alias"), signer);
      assertThatThrownBy(
              () ->
                  new AccountCheckoutReceivingProperties("test", privateRoot, publicRoot, alias, 0))
          .isInstanceOf(IllegalArgumentException.class);
      // A signer alias into custody must also fail, including the ancestor direction.
      Path custodyChild = Files.createDirectory(custody.resolve(signer.getFileName()));
      Path signerAlias =
          Files.createSymbolicLink(keys.resolve(signer.getFileName() + "-custody"), custodyChild);
      assertThatThrownBy(
              () ->
                  new AccountCheckoutReceivingProperties(
                      "test",
                      signer == privateRoot ? signerAlias : privateRoot,
                      signer == publicRoot ? signerAlias : publicRoot,
                      custody,
                      0))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Path missing = keys.resolve("missing");
    Path dangling = Files.createSymbolicLink(keys.resolve("dangling"), missing);
    for (Path invalid :
        java.util.List.of(
            missing,
            dangling,
            custody.resolve("../custody"),
            Path.of("relative"),
            Files.createFile(keys.resolve("file")))) {
      assertThatThrownBy(
              () ->
                  new AccountCheckoutReceivingProperties(
                      "test", privateRoot, publicRoot, invalid, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> new AccountCheckoutReceivingProperties("test", invalid, publicRoot, custody, 0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new AccountCheckoutReceivingProperties("test", privateRoot, invalid, custody, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
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
