package unit.net.firedevops.firemud.accountservice.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.accountservice.maintenance.AccountAuthorityGenerationEnrollmentRunner;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.transaction.annotation.Transactional;

class AccountAuthorityGenerationEnrollmentRunnerTest {
  private final AccountAuthorityGenerationRepository authorityGenerationRepository =
      mock(AccountAuthorityGenerationRepository.class);
  private final AccountAuthorityGenerationEnrollmentRunner runner =
      new AccountAuthorityGenerationEnrollmentRunner(authorityGenerationRepository);

  @Test
  void startupEnrollsExactAccountJwtIssuerAndRequiresExactReadback() {
    AuthorityScope expectedScope = AuthorityScope.issuer("firemud-account-service");
    when(authorityGenerationRepository.initializeIssuerIfAbsent(expectedScope.issuerId()))
        .thenReturn(new ScopeState(expectedScope, 1L, 1L, null));

    runner.run(mock(ApplicationArguments.class));

    verify(authorityGenerationRepository).initializeIssuerIfAbsent("firemud-account-service");
  }

  @Test
  void startupEnrollmentRunsInTransaction() throws ReflectiveOperationException {
    Transactional annotation =
        AccountAuthorityGenerationEnrollmentRunner.class
            .getMethod("run", ApplicationArguments.class)
            .getAnnotation(Transactional.class);

    assertThat(annotation).isNotNull();
  }

  @Test
  void startupFailsWhenIssuerReadbackIsContradictory() {
    when(authorityGenerationRepository.initializeIssuerIfAbsent("firemud-account-service"))
        .thenReturn(new ScopeState(AuthorityScope.issuer("different-issuer"), 1L, 1L, null));

    assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exact issuer");
  }
}
