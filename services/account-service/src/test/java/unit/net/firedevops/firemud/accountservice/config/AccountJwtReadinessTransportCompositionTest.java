package unit.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.account.v1.AccountJwtReadinessPodReceiverServiceGrpc;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessPrerequisiteConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTransportConfiguration;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessGrpcReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeService;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessTransportOwner;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccountJwtReadinessTransportCompositionTest {
  private static final String[] PROTECTED_READINESS_PROPERTIES = {
    "firemud.account.jwt-readiness.validation.max-control-ui-tenant-scopes=256",
    "firemud.account.jwt-jwks-api.enabled=true",
    "firemud.account.jwt-jwks-api.protected-binding-path=/etc/firemud/account-jwt-api/binding.json",
    "firemud.account.jwt-signer.materialization.enabled=true",
    "firemud.account.jwt-signer.materialization.protected-binding-path=/etc/firemud/account-jwt-materializer/binding.json"
  };

  @Test
  void realInvocationPortRequiresSslBundleAndProtectedWorkloadNamespace() {
    protectedTransportRunner()
        .withPropertyValues(PROTECTED_READINESS_PROPERTIES)
        .withPropertyValues(
            "firemud.account.jwt-readiness.validation.enabled=true",
            "firemud.grpc.workload-namespace=firemud-prod")
        .withBean(SslBundles.class, () -> mock(SslBundles.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(AccountJwtReadinessReceiverInvocationPort.class))
                  .isExactlyInstanceOf(AccountJwtReadinessGrpcReceiverInvocationPort.class);
            });

    protectedTransportRunner()
        .withPropertyValues(PROTECTED_READINESS_PROPERTIES)
        .withPropertyValues(
            "firemud.account.jwt-readiness.validation.enabled=true",
            "firemud.grpc.workload-namespace=firemud-prod")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertDefaultDenied(context.getBean(AccountJwtReadinessReceiverInvocationPort.class));
            });

    protectedTransportRunner()
        .withPropertyValues(PROTECTED_READINESS_PROPERTIES)
        .withPropertyValues("firemud.account.jwt-readiness.validation.enabled=true")
        .withBean(SslBundles.class, () -> mock(SslBundles.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessReceiverInvocationPort"))
                  .isFalse();
            });

    protectedTransportRunner()
        .withPropertyValues(PROTECTED_READINESS_PROPERTIES)
        .withPropertyValues("firemud.grpc.workload-namespace=firemud-prod")
        .withBean(SslBundles.class, () -> mock(SslBundles.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBeanFactory()
                          .containsBeanDefinition("accountJwtReadinessReceiverInvocationPort"))
                  .isFalse();
            });
  }

  @Test
  void ownerReadAndCallerCompositionShareOneProbeGraphAndKeepReceiverApisDistinct() {
    protectedTransportRunner()
        .withUserConfiguration(AccountJwtReadinessProbeOwnerConfiguration.class)
        .withPropertyValues(PROTECTED_READINESS_PROPERTIES)
        .withPropertyValues(
            "firemud.account.jwt-readiness.validation.enabled=true",
            "firemud.account.jwt-readiness.probe-owner.enabled=true",
            "firemud.grpc.workload-namespace=firemud-prod")
        .withBean(SslBundles.class, () -> mock(SslBundles.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(AccountJwtReadinessProbeService.class)).hasSize(1);
              assertThat(context.getBeansOfType(AccountJwtValidatorInventorySource.class))
                  .hasSize(1);
              assertThat(context.getBean(AccountJwtReadinessReceiverInvocationPort.class))
                  .isExactlyInstanceOf(AccountJwtReadinessGrpcReceiverInvocationPort.class);
              assertThat(context.getBean(AccountJwtReadinessProbeOwnerService.class)).isNotNull();
              assertThat(context.getBean(AccountJwtReadinessTransportOwner.class)).isNotNull();
            });

    assertThat(
            AccountJwtReadinessPodReceiverServiceGrpc.getReceiveReadinessProbeMethod()
                .getFullMethodName())
        .isEqualTo("account.v1.AccountJwtReadinessPodReceiverService/ReceiveReadinessProbe");
    assertThat(
            GameSessionJwtReadinessReceiverServiceGrpc.getReceiveReadinessProbeMethod()
                .getFullMethodName())
        .isEqualTo("game_session.v1.GameSessionJwtReadinessReceiverService/ReceiveReadinessProbe");
    assertThat(
            AccountJwtReadinessPodReceiverServiceGrpc.getReceiveReadinessProbeMethod()
                .getFullMethodName())
        .isNotEqualTo(
            GameSessionJwtReadinessReceiverServiceGrpc.getReceiveReadinessProbeMethod()
                .getFullMethodName());
  }

  private static ApplicationContextRunner protectedTransportRunner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            AccountJwtReadinessPrerequisiteConfiguration.class,
            AccountJwtReadinessTransportConfiguration.class,
            AccountJwtReadinessTestDependencies.class);
  }

  private static void assertDefaultDenied(AccountJwtReadinessReceiverInvocationPort port) {
    assertThat(port).isNotInstanceOf(AccountJwtReadinessGrpcReceiverInvocationPort.class);
    assertThatThrownBy(port::requireAvailable)
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class)
        .hasNoCause();
  }
}
