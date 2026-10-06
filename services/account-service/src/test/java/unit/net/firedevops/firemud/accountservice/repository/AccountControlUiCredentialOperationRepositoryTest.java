package net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequest;
import net.firedevops.firemud.accountservice.dto.AccountControlUiIssuanceRequestDigest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountEmailLoginChallenge;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.service.controlui.AccountControlUiAuthenticationRequest.Purpose;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL-call unit proof only; real migration/concurrency/rollback proof remains PostgreSQL-owned. */
class AccountControlUiCredentialOperationRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountControlUiCredentialOperationRepository repository =
      new AccountControlUiCredentialOperationRepository(dsl);
  private final UUID requestId = UUID.randomUUID();
  private final UUID operationId = UUID.randomUUID();
  private final Account account = new Account();
  private final Record operation = mock(Record.class);

  @BeforeEach
  void prepareCurrentOriginalOperation() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    account.setId(9L);
    account.setAccountUuid(UUID.randomUUID());
    account.setAccountUuidSourceNumericId(9L);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    when(operation.get("operation_id", UUID.class)).thenReturn(operationId);
    when(operation.get("request_id", UUID.class)).thenReturn(requestId);
    when(operation.get("account_uuid", UUID.class)).thenReturn(account.getAccountUuid());
    when(operation.get("account_id", Long.class)).thenReturn(9L);
    when(operation.get("account_provenance", String.class))
        .thenReturn(account.getAccountUuidProvenance().name());
    when(operation.get("profile", String.class)).thenReturn("control-ui");
    when(operation.get("audience", String.class)).thenReturn("control-ui");
    when(operation.get("request_digest_version", Integer.class)).thenReturn(1);
    when(operation.get("request_digest", byte[].class))
        .thenReturn(
            AccountControlUiIssuanceRequestDigest.digest(
                new AccountControlUiIssuanceRequest(
                    requestId.toString(), account.getAccountUuid().toString())));
    when(operation.get("status", String.class)).thenReturn("PENDING");
    when(operation.get("expires_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.now().plusMinutes(5));
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              if (sql.startsWith("SELECT")) return operation;
              if (sql.startsWith("DELETE")) {
                Record consumed = mock(Record.class);
                when(consumed.get("id", Long.class)).thenReturn(73L);
                return consumed;
              }
              Record inserted = mock(Record.class);
              when(inserted.get("attempt_id", UUID.class)).thenReturn(call.getArgument(1));
              when(inserted.get("operation_id", UUID.class)).thenReturn(operationId);
              when(inserted.get("authenticated_at", OffsetDateTime.class))
                  .thenReturn(OffsetDateTime.now(ZoneOffset.UTC));
              return inserted;
            });
  }

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void passwordAttemptBindsTheOriginalOperationWithoutChallengeDeletion() {
    var result =
        repository.recordVerifiedCredential(
            requestId, account, Purpose.INITIAL_ISSUANCE, Optional.empty());
    assertEquals(operationId, result.operationId());
    assertEquals("PASSWORD", result.method());
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl, org.mockito.Mockito.times(2)).fetchOne(sql.capture(), any(Object[].class));
    org.junit.jupiter.api.Assertions.assertTrue(
        sql.getAllValues().stream().noneMatch(value -> value.startsWith("DELETE")));
  }

  @Test
  void matchedOtpDeletesOnlyTheExactVerifiedChallengeBeforeEvidenceInsertion() {
    var challenge = new AccountEmailLoginChallenge();
    challenge.setId(73L);
    challenge.setAccountId(9L);
    challenge.setCodeHash("actual-owner-verified-hash");
    challenge.setExpiresAt(LocalDateTime.now().plusMinutes(5));
    var result =
        repository.recordVerifiedCredential(
            requestId, account, Purpose.INITIAL_ISSUANCE, Optional.of(challenge));
    assertEquals("EMAIL_OTP", result.method());
    var order = org.mockito.Mockito.inOrder(dsl);
    order
        .verify(dsl)
        .fetchOne(org.mockito.ArgumentMatchers.startsWith("SELECT"), any(Object[].class));
    order
        .verify(dsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.startsWith("DELETE"),
            org.mockito.ArgumentMatchers.eq(73L),
            org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.eq("actual-owner-verified-hash"),
            any(LocalDateTime.class));
    order
        .verify(dsl)
        .fetchOne(org.mockito.ArgumentMatchers.startsWith("INSERT"), any(Object[].class));
  }

  @Test
  void freshPasswordRecoveryRecordsANewAttemptForTheExactCommittedOperation() {
    when(operation.get("status", String.class)).thenReturn("COMMITTED");
    var first =
        repository.recordVerifiedCredential(
            requestId, account, Purpose.EXACT_RESPONSE_RECOVERY, Optional.empty());
    var second =
        repository.recordVerifiedCredential(
            requestId, account, Purpose.EXACT_RESPONSE_RECOVERY, Optional.empty());
    assertEquals(operationId, first.operationId());
    assertEquals(operationId, second.operationId());
    org.junit.jupiter.api.Assertions.assertNotEquals(first.attemptId(), second.attemptId());
  }

  @Test
  void missingOriginalOperationCannotConsumeOrCreateEvidence() {
    when(dsl.fetchOne(org.mockito.ArgumentMatchers.startsWith("SELECT"), any(Object[].class)))
        .thenReturn(null);
    assertConflict(Purpose.INITIAL_ISSUANCE);
  }

  @Test
  void crossAccountOperationFailsBeforeConsumptionOrEvidence() {
    when(operation.get("account_uuid", UUID.class)).thenReturn(UUID.randomUUID());
    assertConflict(Purpose.INITIAL_ISSUANCE);
  }

  @Test
  void changedSemanticDigestFailsBeforeConsumptionOrEvidence() {
    when(operation.get("request_digest", byte[].class)).thenReturn(new byte[32]);
    assertConflict(Purpose.INITIAL_ISSUANCE);
  }

  @Test
  void pendingOperationCannotAuthenticateExactCommittedResponseRecovery() {
    assertConflict(Purpose.EXACT_RESPONSE_RECOVERY);
  }

  @Test
  void expiredOriginalResultCannotAuthenticateRecovery() {
    when(operation.get("status", String.class)).thenReturn("COMMITTED");
    when(operation.get("expires_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.now().minusSeconds(1));
    assertConflict(Purpose.EXACT_RESPONSE_RECOVERY);
  }

  @Test
  void unavailableOrAlreadyConsumedOtpCannotCreateAuthenticationEvidence() {
    when(dsl.fetchOne(org.mockito.ArgumentMatchers.startsWith("DELETE"), any(Object[].class)))
        .thenReturn(null);
    var challenge = new AccountEmailLoginChallenge();
    challenge.setId(73L);
    challenge.setAccountId(9L);
    challenge.setCodeHash("actual-owner-verified-hash");
    assertThrows(
        AccountControlUiCredentialOperationRepository.CredentialOperationConflictException.class,
        () ->
            repository.recordVerifiedCredential(
                requestId, account, Purpose.INITIAL_ISSUANCE, Optional.of(challenge)));
    verify(dsl, org.mockito.Mockito.never())
        .fetchOne(org.mockito.ArgumentMatchers.startsWith("INSERT"), any(Object[].class));
  }

  @Test
  void recordingRequiresWritableOwnerTransaction() {
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.recordVerifiedCredential(
                requestId, account, Purpose.INITIAL_ISSUANCE, Optional.empty()));
    org.mockito.Mockito.verifyNoInteractions(dsl);
  }

  private void assertConflict(Purpose purpose) {
    assertThrows(
        AccountControlUiCredentialOperationRepository.CredentialOperationConflictException.class,
        () -> repository.recordVerifiedCredential(requestId, account, purpose, Optional.empty()));
    verify(dsl, org.mockito.Mockito.times(1)).fetchOne(anyString(), any(Object[].class));
  }
}
