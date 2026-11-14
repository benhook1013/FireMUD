package net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountControlUiAccountAuthorityTest {
  @Test
  void realAccountOnlySourceHasNoTenantCollaboratorAndPreservesExactCapture() throws Exception {
    var f = new AccountControlUiAuthorityTest.AccountCaptureContext();
    var source =
        new AccountControlUiAccountAuthority(f.accounts, f.upstream, f.restrictions, f.dsl);
    byte[] existing = f.capture().evidence();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      var actual = source.captureAccount(f.id);
      assertThat(actual.evidence()).isEqualTo(existing);
      assertThat(actual.optionalTenantId()).isEmpty();
      assertThat(actual.scopedRoles()).isEmpty();
      assertThat(source).isNotInstanceOf(AccountControlUiAuthority.class);
      verifyNoInteractions(f.drafts);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void standaloneAccountSourcePreservesWritableTransactionRequirement() throws Exception {
    var f = new AccountControlUiAuthorityTest.AccountCaptureContext();
    var source =
        new AccountControlUiAccountAuthority(f.accounts, f.upstream, f.restrictions, f.dsl);
    assertThatThrownBy(() -> source.captureAccount(f.id)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(f.accounts, f.upstream, f.restrictions);
  }
}
