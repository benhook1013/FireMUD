package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
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
  void lockedOwnerReadRejectsUnpersistedIdsBeforeAnySql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository source =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountRepository repository = new AccountRepository(dsl, source);
    for (Long id : new Long[] {null, 0L, -1L}) {
      assertThatThrownBy(() -> repository.findByIdForUpdate(id))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(dsl, source);
  }

  @Test
  void lockedOwnerReadSelectsTheActualRowForUpdateWithoutSourceMutation() throws Exception {
    java.util.List<String> observed = new java.util.ArrayList<>();
    var connection =
        new org.jooq.tools.jdbc.MockConnection(
            context -> {
              observed.add(context.sql());
              return new org.jooq.tools.jdbc.MockResult[] {
                new org.jooq.tools.jdbc.MockResult(
                    0,
                    org.jooq
                        .impl
                        .DSL
                        .using(org.jooq.SQLDialect.POSTGRES)
                        .newResult(net.firedevops.firemud.accountservice.jooq.Tables.ACCOUNTS.ID))
              };
            });
    AccountAuthoritySourceEvidenceRepository source =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    try (connection) {
      AccountRepository repository =
          new AccountRepository(
              org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES), source);
      assertThat(repository.findByIdForUpdate(42L)).isEmpty();
    }
    assertThat(observed).hasSize(1);
    assertThat(observed.getFirst()).contains("accounts", "for update");
    verifyNoInteractions(source);
  }

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
