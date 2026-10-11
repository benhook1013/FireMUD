package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountAuthEvidenceBundleRepositoryTest {
  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresWritableAccountTransactionBeforeAnyOwnerRead() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuthEvidenceBundleRepository repository =
        new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);

    assertThatThrownBy(() -> repository.captureAndPersist(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");
    verifyNoInteractions(dsl, generations, outbox);
  }

  @Test
  void absentOwnerOperationDeniesWithoutWritingOrAcceptingCallerBundleBytes() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuthEvidenceBundleRepository repository =
        new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);
    UUID requestId = UUID.randomUUID();
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(null);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> repository.captureAndPersist(requestId))
        .isInstanceOf(AccountAuthEvidenceBundleRepository.OperationNotFoundException.class);
    verify(dsl)
        .fetchOne(
            contains("FROM account_gameplay_delegation_issuance_operations"), any(Object[].class));
    verify(dsl, never()).execute(anyString(), any(Object[].class));
    verifyNoInteractions(generations, outbox);
  }

  @Test
  void locksAccountAndAuthorityBeforeLockingAndRevalidatingOperation() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    AccountAuthorityOutboxRepository outbox = mock(AccountAuthorityOutboxRepository.class);
    AccountAuthEvidenceBundleRepository repository =
        new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    Record preliminary = mock(Record.class);
    when(preliminary.get("request_id", UUID.class)).thenReturn(requestId);
    when(preliminary.get("operation_id", UUID.class)).thenReturn(operationId);
    when(preliminary.get("account_uuid", UUID.class)).thenReturn(accountId);

    Record account = mock(Record.class);
    when(account.get("id", Long.class)).thenReturn(1L);
    when(account.get("account_uuid", UUID.class)).thenReturn(accountId);
    when(account.get("account_uuid_provenance", String.class))
        .thenReturn(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name());
    when(account.get("account_uuid_source_numeric_id", Long.class)).thenReturn(1L);

    Record lockedOperation = mock(Record.class);
    when(lockedOperation.get("request_id", UUID.class)).thenReturn(requestId);
    when(lockedOperation.get("operation_id", UUID.class)).thenReturn(operationId);
    when(lockedOperation.get("account_uuid", UUID.class)).thenReturn(accountId);
    when(lockedOperation.get("caller_context_id", UUID.class)).thenReturn(UUID.randomUUID());
    when(lockedOperation.get("token_jti", UUID.class)).thenReturn(UUID.randomUUID());
    when(lockedOperation.get("request_digest", String.class)).thenReturn("a".repeat(64));
    when(lockedOperation.get("caller_workload", String.class))
        .thenReturn("spiffe://firemud/ns/test/sa/game-session-service");
    when(lockedOperation.get("status", String.class)).thenReturn("COMMITTED");
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenReturn(preliminary, account, lockedOperation);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> repository.captureAndPersist(requestId))
        .isInstanceOf(AccountAuthEvidenceBundleRepository.OwnerEvidenceUnavailableException.class);

    var order = inOrder(dsl, generations);
    order
        .verify(dsl)
        .fetchOne(
            argThat(
                sql ->
                    sql.contains("SELECT operation_id, request_id, account_uuid")
                        && sql.contains("FROM account_gameplay_delegation_issuance_operations")
                        && !sql.contains("FOR UPDATE")),
            any(Object[].class));
    order
        .verify(dsl)
        .fetchOne(
            argThat(sql -> sql.contains("FROM accounts") && sql.contains("FOR SHARE")),
            any(Object[].class));
    order
        .verify(generations)
        .readCompositeSnapshot(
            eq(GameSessionAccountDelegationProfile.ISSUER),
            eq(accountId),
            eq(java.util.List.of()),
            eq(java.util.List.of()));
    order
        .verify(dsl)
        .fetchOne(
            argThat(
                sql ->
                    sql.contains("FROM account_gameplay_delegation_issuance_operations")
                        && sql.contains("FOR UPDATE")),
            any(Object[].class));
    verifyNoInteractions(outbox);
  }

  @Test
  void storedValueReaderIsNonAuthorizingAndFailsClosedWhenNoReadbackExists() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthEvidenceBundleRepository repository =
        new AccountAuthEvidenceBundleRepository(
            dsl,
            mock(AccountAuthorityGenerationRepository.class),
            mock(AccountAuthorityOutboxRepository.class));
    UUID operationId = UUID.randomUUID();
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(null);

    assertThatThrownBy(() -> repository.readStoredNonAuthorizingValue(operationId))
        .isInstanceOf(AccountAuthEvidenceBundleRepository.StoredBundleUnavailableException.class);
  }
}
