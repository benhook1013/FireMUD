package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class AccountRepositoryTest {
  @Test
  void hardDeletionFailsClosedWithoutSqlOrSourceEvidenceMutation() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, sourceEvidence);
    Account account = new Account();
    account.setId(42L);

    assertThatThrownBy(() -> repository.delete(account))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "Account hard deletion is unavailable until the pending-deletion retention workflow exists");

    verifyNoInteractions(dsl, sourceEvidence);
  }
}
