package net.firedevops.firemud.accountservice.maintenance;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Enrolls and reads back Account's exact JWT issuer generation before the service starts. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected AccountAuthorityGenerationRepository is an internal Spring collaborator.")
@Component
public class AccountAuthorityGenerationEnrollmentRunner implements ApplicationRunner {
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;

  public AccountAuthorityGenerationEnrollmentRunner(
      AccountAuthorityGenerationRepository authorityGenerationRepository) {
    this.authorityGenerationRepository = authorityGenerationRepository;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    String issuerId = AccountServiceImpl.ACCOUNT_JWT_ISSUER;
    AuthorityScope expectedScope = AuthorityScope.issuer(issuerId);
    ScopeState persisted = authorityGenerationRepository.initializeIssuerIfAbsent(issuerId);
    if (persisted == null || !expectedScope.equals(persisted.scope())) {
      throw new IllegalStateException(
          "Account issuer authority readback did not match exact issuer");
    }
  }
}
