package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Checkpoint;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository.Event;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceEvidenceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountAuthoritySourceEventReadback;
import org.junit.jupiter.api.Test;

class AccountAuthoritySourceEventReadbackTest {
  @Test
  void unsupportedGenericAccountEventMapsToTypedUnavailableEvidence() {
    UUID accountUuid = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String stream = "account:auth-authority:v1:account/" + accountUuid;
    String eventId = "generic-account-event-1";
    String eventDigest = "sha256:" + "c".repeat(64);
    Event event =
        new Event(
            stream,
            "generic-account-request-1",
            1L,
            eventId,
            eventDigest,
            "{\"schemaVersion\":\"account-auth-authority-source-event/v1\"}"
                .getBytes(StandardCharsets.UTF_8));
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountPasswordResetOperationRepository passwordResets =
        mock(AccountPasswordResetOperationRepository.class);
    AccountLogoutAllOperationRepository logoutAll = mock(AccountLogoutAllOperationRepository.class);
    AccountSecurityStateOperationRepository securityState =
        mock(AccountSecurityStateOperationRepository.class);
    when(outbox.readCheckpoint(stream))
        .thenReturn(Optional.of(new Checkpoint(stream, 1L, eventId, eventDigest)));
    when(outbox.findEvent(stream, 1L)).thenReturn(Optional.of(event));
    var readback =
        new AccountAuthoritySourceEventReadback(outbox, passwordResets, logoutAll, securityState);
    Account account = new Account();
    account.setId(42L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    account.setAccountUuidSourceNumericId(42L);
    ScopeState current =
        new ScopeState(
            AuthorityScope.account(accountUuid), 2L, 2L, new IssuanceFence(accountUuid, 5L, 8L));

    assertThatThrownBy(() -> readback.requireCurrentLatest(account, current))
        .isExactlyInstanceOf(SourceEvidenceUnavailableException.class);

    verifyNoInteractions(passwordResets, logoutAll, securityState);
  }
}
