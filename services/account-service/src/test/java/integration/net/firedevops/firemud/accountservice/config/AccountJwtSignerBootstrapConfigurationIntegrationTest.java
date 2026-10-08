package integration.net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerBootstrapConfiguration;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator.BootstrapOperationException;
import net.firedevops.firemud.accountservice.service.session.AccountJwtSignerBootstrapCoordinator.FailureCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class AccountJwtSignerBootstrapConfigurationIntegrationTest {
  @Test
  void enabledCompositionIsCallableButDoesNotReadTrustOrStartReconciliationAtContextStartup() {
    AccountJwtSignerMaterializerTrustBinding materializerTrust =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    AccountJwtSignerDesiredStateRepository desiredStateRepository =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtJwksPublicationRepository publicationRepository =
        mock(AccountJwtJwksPublicationRepository.class);
    AccountJwtReadinessProbeRepository readinessProbeRepository =
        mock(AccountJwtReadinessProbeRepository.class);
    when(materializerTrust.current()).thenReturn(Optional.empty());

    new ApplicationContextRunner()
        .withUserConfiguration(AccountJwtSignerBootstrapConfiguration.class)
        .withBean(AccountJwtSignerMaterializerTrustBinding.class, () -> materializerTrust)
        .withBean(AccountJwtSignerDesiredStateRepository.class, () -> desiredStateRepository)
        .withBean(AccountJwtJwksPublicationRepository.class, () -> publicationRepository)
        .withBean(AccountJwtReadinessProbeRepository.class, () -> readinessProbeRepository)
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
        .withPropertyValues("firemud.account.jwt-signer.bootstrap.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(AccountJwtSignerBootstrapCoordinator.class);
              verifyNoInteractions(
                  materializerTrust,
                  desiredStateRepository,
                  publicationRepository,
                  readinessProbeRepository);

              AccountJwtSignerBootstrapCoordinator coordinator =
                  context.getBean(AccountJwtSignerBootstrapCoordinator.class);
              verifyNoInteractions(
                  materializerTrust,
                  desiredStateRepository,
                  publicationRepository,
                  readinessProbeRepository);

              assertThatThrownBy(coordinator::enrollOnce)
                  .isInstanceOf(BootstrapOperationException.class)
                  .extracting(failure -> ((BootstrapOperationException) failure).failureCode())
                  .isEqualTo(FailureCode.PROTECTED_BINDING_UNAVAILABLE);
              verify(materializerTrust).current();
              verifyNoInteractions(
                  desiredStateRepository, publicationRepository, readinessProbeRepository);
            });
  }
}
