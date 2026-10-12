package net.firedevops.firemud.accountservice.service.session;

import java.nio.file.Path;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import org.springframework.transaction.PlatformTransactionManager;

/** Narrow test-only access to the existing RSA-backed signer fixture for composition tests. */
public final class AccountGameplayDelegationSignerTestFixtureFactory {
  private AccountGameplayDelegationSignerTestFixtureFactory() {}

  public static Fixture create(Path temporaryDirectory) throws Exception {
    return new Fixture(new AccountGameplayDelegationSignerTest.Fixture(temporaryDirectory, false));
  }

  public static final class Fixture {
    private final AccountGameplayDelegationSignerTest.Fixture delegate;

    private Fixture(AccountGameplayDelegationSignerTest.Fixture delegate) {
      this.delegate = delegate;
    }

    public AccountGameplayDelegationSigner signer() {
      return delegate.signer();
    }

    public AccountGameplayDelegationIssuanceRepository issuanceRepository() {
      return delegate.issuanceRepository();
    }

    public AccountGameplayDelegationResponseEnvelopeService responseEnvelopeService() {
      return delegate.responseEnvelopeService();
    }

    public PlatformTransactionManager transactionManager() {
      return delegate.transactionManager();
    }

    public UUID requestId() {
      return delegate.requestId();
    }

    public UUID accountId() {
      return delegate.accountId();
    }
  }
}
