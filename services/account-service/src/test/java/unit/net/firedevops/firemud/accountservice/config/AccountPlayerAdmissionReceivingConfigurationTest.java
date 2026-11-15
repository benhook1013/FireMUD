package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountPlayerAdmissionEntitlementGrpcService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccountPlayerAdmissionReceivingConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountPlayerAdmissionReceivingConfiguration.class);

  @Test
  void absentByDefault() {
    context.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx).doesNotHaveBean(AccountPlayerAdmissionEntitlementGrpcService.class);
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spring.profiles.active=isolated-player-admission-receiving",
        "firemud.account.player-admission-receiving.enabled=true"
      })
  void neitherSelectionAloneRegistersReceiver(String selection) {
    context
        .withPropertyValues(selection)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(AccountPlayerAdmissionEntitlementGrpcService.class);
            });
  }

  @Test
  void enabledWithoutSourceOwnerFailsClosed() {
    enabled()
        .withPropertyValues("firemud.grpc.workload-namespace=test")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void enabledWithoutNamespaceFailsClosed() {
    enabled()
        .withBean(
            AccountPlayerAdmissionEntitlementOwner.class,
            () -> mock(AccountPlayerAdmissionEntitlementOwner.class))
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void explicitReceivingUsesOnlySuppliedOwnerWithoutProvisioningAuthority() {
    var owner = mock(AccountPlayerAdmissionEntitlementOwner.class);
    enabled()
        .withPropertyValues("firemud.grpc.workload-namespace=test")
        .withBean(AccountPlayerAdmissionEntitlementOwner.class, () -> owner)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).hasSingleBean(AccountPlayerAdmissionEntitlementGrpcService.class);
              assertThat(
                      ctx.getBean(AccountPlayerAdmissionEntitlementGrpcService.class)
                          .bindService()
                          .getMethods())
                  .hasSize(1);
              verifyNoInteractions(owner);
            });
  }

  private ApplicationContextRunner enabled() {
    return context.withPropertyValues(
        "spring.profiles.active=isolated-player-admission-receiving",
        "firemud.account.player-admission-receiving.enabled=true");
  }
}
