package net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountPlayerBootstrapCallerVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled adapter proof; genuine signature/session verification belongs to the existing owner.
 */
class AccountCharacterCreatePrincipalTest {
  private final AccountPlayerBootstrapCallerVerifier verifier =
      mock(AccountPlayerBootstrapCallerVerifier.class);
  private static final String token = "sensitive-bootstrap-token";
  private final UUID account = UUID.randomUUID();

  @AfterEach
  void resetTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void revalidatesExactCredentialOnEveryUseAndNeverPrintsIt() {
    var caller = mock(AccountPlayerBootstrapCallerVerifier.VerifiedCaller.class);
    when(caller.accountUuid()).thenReturn(account);
    when(verifier.requireCaller(token)).thenReturn(caller);
    var principal = AccountCharacterCreatePrincipal.authenticate(verifier, token);
    assertThat(principal.authenticateCurrentAccount()).isEqualTo(account);
    assertThat(principal.authenticateCurrentAccount()).isEqualTo(account);
    verify(verifier, times(3)).requireCaller(token);
    assertThat(principal.toString()).doesNotContain(token);
  }

  @Test
  void revokedCredentialCannotReusePreviouslyAuthenticatedIdentity() {
    var caller = mock(AccountPlayerBootstrapCallerVerifier.VerifiedCaller.class);
    when(caller.accountUuid()).thenReturn(account);
    when(verifier.requireCaller(token)).thenReturn(caller);
    var principal = AccountCharacterCreatePrincipal.authenticate(verifier, token);
    var revoked = new IllegalStateException("revoked");
    when(verifier.requireCaller(token)).thenThrow(revoked);
    assertThatThrownBy(principal::authenticateCurrentAccount).isSameAs(revoked);
  }

  @Test
  void differentOwnerCannotReplaceOriginalIdentity() {
    var caller = mock(AccountPlayerBootstrapCallerVerifier.VerifiedCaller.class);
    when(caller.accountUuid()).thenReturn(account, UUID.randomUUID());
    when(verifier.requireCaller(token)).thenReturn(caller);
    var principal = AccountCharacterCreatePrincipal.authenticate(verifier, token);
    assertThatThrownBy(principal::authenticateCurrentAccount)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Bootstrap Account identity changed");
  }

  @Test
  void ambientTransactionNeverCallsCredentialOwner() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> AccountCharacterCreatePrincipal.authenticate(verifier, token))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(verifier);
  }

  @Test
  void ambientSynchronizationNeverCallsCredentialOwner() {
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> AccountCharacterCreatePrincipal.authenticate(verifier, token))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(verifier);
  }
}
