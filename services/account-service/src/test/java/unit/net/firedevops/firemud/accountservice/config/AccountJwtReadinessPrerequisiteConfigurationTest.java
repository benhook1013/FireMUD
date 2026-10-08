package unit.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPrerequisiteConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccountJwtReadinessPrerequisiteConfigurationTest {
  private static final String[] VALID_PREREQUISITE_PROPERTIES = {
    "firemud.account.jwt-readiness.validation.enabled=true",
    "firemud.account.jwt-jwks-api.enabled=true",
    "firemud.account.jwt-jwks-api.protected-binding-path=/etc/firemud/account-jwt-api/binding.json",
    "firemud.account.jwt-signer.materialization.enabled=true",
    "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json",
    "firemud.grpc.workload-namespace=firemud-prod"
  };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(AccountJwtReadinessPrerequisiteConfiguration.class);

  @Test
  void prerequisiteGraphIsAbsentWithoutExplicitReadinessAndProtectedBindings() {
    contextRunner
        .withPropertyValues(
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path=/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.grpc.workload-namespace=firemud-prod")
        .run(AccountJwtReadinessPrerequisiteConfigurationTest::assertNoPrerequisiteBeans);

    contextRunner
        .withPropertyValues("firemud.account.jwt-readiness.validation.enabled=true")
        .run(AccountJwtReadinessPrerequisiteConfigurationTest::assertNoPrerequisiteBeans);

    contextRunner
        .withPropertyValues(
            "firemud.account.gameplay-canonical-login.readiness.enabled=true",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path=/etc/firemud/account-jwt-api/binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.grpc.workload-namespace=firemud-prod")
        .run(AccountJwtReadinessPrerequisiteConfigurationTest::assertNoPrerequisiteBeans);
  }

  @Test
  void exactExplicitReadinessCompositionRegistersOnlyLazyProtectedPrerequisites() {
    contextRunner
        .withPropertyValues(VALID_PREREQUISITE_PROPERTIES)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertLazyBean(context, "accountJwtReadinessJwksApiBinding");
              assertLazyBean(context, "accountJwtReadinessJwksConfigMapClient");
              assertLazyBean(context, "accountJwtReadinessTrustedJwksSource");
              assertThat(
                      context
                          .getBeanFactory()
                          .containsSingleton("accountJwtReadinessJwksApiBinding"))
                  .isFalse();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsSingleton("accountJwtReadinessJwksConfigMapClient"))
                  .isFalse();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsSingleton("accountJwtReadinessTrustedJwksSource"))
                  .isFalse();
            });
  }

  @Test
  void invalidNamespaceOrChangedBindingPathsKeepPrerequisitesUnavailable() {
    contextRunner
        .withPropertyValues(
            VALID_PREREQUISITE_PROPERTIES[0],
            VALID_PREREQUISITE_PROPERTIES[1],
            VALID_PREREQUISITE_PROPERTIES[2],
            VALID_PREREQUISITE_PROPERTIES[3],
            VALID_PREREQUISITE_PROPERTIES[4],
            "firemud.grpc.workload-namespace=INVALID_NAMESPACE")
        .run(AccountJwtReadinessPrerequisiteConfigurationTest::assertNoPrerequisiteBeans);

    contextRunner
        .withPropertyValues(
            "firemud.account.jwt-readiness.pod-receiver.enabled=true",
            "firemud.account.jwt-jwks-api.enabled=true",
            "firemud.account.jwt-jwks-api.protected-binding-path=/tmp/other-binding.json",
            "firemud.account.jwt-signer.materialization.enabled=true",
            "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json",
            "firemud.grpc.workload-namespace=firemud-prod")
        .run(AccountJwtReadinessPrerequisiteConfigurationTest::assertNoPrerequisiteBeans);
  }

  private static void assertNoPrerequisiteBeans(AssertableApplicationContext context) {
    assertThat(context).hasNotFailed();
    assertThat(context.getBeanFactory().containsBeanDefinition("accountJwtReadinessJwksApiBinding"))
        .isFalse();
    assertThat(
            context
                .getBeanFactory()
                .containsBeanDefinition("accountJwtReadinessJwksConfigMapClient"))
        .isFalse();
    assertThat(
            context.getBeanFactory().containsBeanDefinition("accountJwtReadinessTrustedJwksSource"))
        .isFalse();
  }

  private static void assertLazyBean(AssertableApplicationContext context, String beanName) {
    assertThat(context.getBeanFactory().containsBeanDefinition(beanName)).isTrue();
    assertThat(context.getBeanFactory().getBeanDefinition(beanName).isLazyInit()).isTrue();
  }
}
