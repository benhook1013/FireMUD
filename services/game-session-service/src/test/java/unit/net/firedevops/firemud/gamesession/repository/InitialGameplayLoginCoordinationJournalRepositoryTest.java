package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.ProofKind;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import net.firedevops.firemud.gamesession.repository.InitialGameplayLoginCoordinationJournalRepository.JournalConflictException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SQL-record doubles exercise source decisions only; native persistence proof is separate. */
class InitialGameplayLoginCoordinationJournalRepositoryTest {
  private static final InitialGameplayLoginAttemptCoordinates ORIGINAL =
      new InitialGameplayLoginAttemptCoordinates(uuid(1), uuid(2), 7);

  @AfterEach
  void clearTransactions() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsMutationWithoutWritableReadCommittedTransactionBeforeAnySql() {
    var dsl = mock(DSLContext.class);
    var journal = new InitialGameplayLoginCoordinationJournalRepository(dsl);
    assertThatThrownBy(() -> journal.reserve(ORIGINAL)).isInstanceOf(IllegalStateException.class);
    writableTransaction();
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> journal.abandon(ORIGINAL)).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_REPEATABLE_READ);
    assertThatThrownBy(() -> journal.freezeRetirementRequest(ORIGINAL))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void committedReadAndInventoryRejectAnAmbientTransactionBeforeSql() {
    var dsl = mock(DSLContext.class);
    var journal = new InitialGameplayLoginCoordinationJournalRepository(dsl);
    writableTransaction();
    assertThatThrownBy(() -> journal.readCommitted(ORIGINAL))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> journal.readRetainedPage(java.util.Optional.empty(), 10))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @ParameterizedTest
  @ValueSource(strings = {"request", "context", "fence"})
  void conflictingReuseCannotAdoptRetainedOriginal(String changed) {
    RowFixture fixture = new RowFixture();
    switch (changed) {
      case "request" -> fixture.columns.put("request_id", uuid(8));
      case "context" -> fixture.columns.put("caller_context_id", uuid(8));
      case "fence" -> fixture.columns.put("expected_attempt_fence", 8L);
      default -> throw new AssertionError(changed);
    }
    writableTransaction();
    assertThatThrownBy(() -> fixture.journal.abandon(ORIGINAL))
        .isInstanceOf(JournalConflictException.class);
    verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void cannotFreezeBeforeIrreversibleAbandonment() {
    RowFixture fixture = new RowFixture();
    writableTransaction();
    assertThatThrownBy(() -> fixture.journal.freezeRetirementRequest(ORIGINAL))
        .isInstanceOf(JournalConflictException.class);
    verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void frozenAbsenceSurvivesLateVerifiedWitnessAndExactRetry() {
    RowFixture fixture = new RowFixture();
    fixture.columns.put("abandoned", true);
    var originalRequest = InitialGameplayLoginJournalSnapshot.request(ORIGINAL, null);
    fixture.columns.put("retirement_request_bytes", originalRequest.toByteArray());
    // Only this controlled validated-result boundary is doubled; no signature/custody claim.
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence())
        .thenReturn(
            new CanonicalGameplayLoginResult.NonSecretEvidence(
                uuid(5),
                ORIGINAL.requestId(),
                ORIGINAL.callerContextId(),
                uuid(4),
                "a".repeat(64),
                "game-session-account-delegation",
                1,
                1,
                2,
                "1",
                "19",
                ByteString.EMPTY,
                ByteString.EMPTY,
                ByteString.EMPTY));
    when(fixture.dsl.execute(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              assertThat(sql)
                  .contains("SET verified_token_jti")
                  .doesNotContain("retirement_request_bytes");
              fixture.columns.put("verified_token_jti", uuid(4));
              fixture.columns.put("verified_token_sha256", "a".repeat(64));
              fixture.columns.put("verified_token_generation", 1L);
              fixture.columns.put("verified_issuance_fence", 19L);
              return 1;
            });
    writableTransaction();
    var late = fixture.journal.recordVerifiedResult(ORIGINAL, result);
    assertThat(late.verifiedWitness()).isPresent();
    assertThat(late.abandoned()).isTrue();
    assertThat(late.frozenRetirementRequest()).contains(originalRequest);
    assertThat(fixture.journal.freezeRetirementRequest(ORIGINAL).frozenRetirementRequest())
        .contains(originalRequest);
    verify(result, never()).compactJwtBytes();
  }

  @Test
  void changedVerifiedWitnessConflictsBeforeOverwritingItsOriginalEvidence() {
    RowFixture fixture = new RowFixture();
    fixture.columns.put("verified_token_jti", uuid(4));
    fixture.columns.put("verified_token_sha256", "a".repeat(64));
    fixture.columns.put("verified_token_generation", 1L);
    fixture.columns.put("verified_issuance_fence", 19L);
    var result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence())
        .thenReturn(
            new CanonicalGameplayLoginResult.NonSecretEvidence(
                uuid(5),
                ORIGINAL.requestId(),
                ORIGINAL.callerContextId(),
                uuid(8),
                "b".repeat(64),
                "game-session-account-delegation",
                1,
                1,
                2,
                "1",
                "19",
                ByteString.EMPTY,
                ByteString.EMPTY,
                ByteString.EMPTY));
    writableTransaction();
    assertThatThrownBy(() -> fixture.journal.recordVerifiedResult(ORIGINAL, result))
        .isInstanceOf(JournalConflictException.class);
    verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"operation", "closed-pair", "partial", "unknown-wire"})
  void malformedOrConflictingReceiptCannotReplaceRetainedTerminalEvidence(String change) {
    RowFixture fixture = new RowFixture();
    fixture.columns.put("abandoned", true);
    fixture.columns.put(
        "retirement_request_bytes",
        InitialGameplayLoginJournalSnapshot.request(ORIGINAL, null).toByteArray());
    fixture.columns.put("disposal_operation_id", uuid(3));
    fixture.columns.put("disposal_disposition", "RETIRED");
    fixture.columns.put("disposal_proof_kind", "PHYSICAL_TOKEN_DENIAL");
    var receipt =
        new InitialGameplayLoginDisposalResult(
            uuid(1), uuid(2), uuid(3), Disposition.RETIRED, ProofKind.PHYSICAL_TOKEN_DENIAL);
    switch (change) {
      case "operation" ->
          receipt =
              new InitialGameplayLoginDisposalResult(
                  uuid(1), uuid(2), uuid(8), Disposition.RETIRED, ProofKind.PHYSICAL_TOKEN_DENIAL);
      case "closed-pair" ->
          receipt =
              new InitialGameplayLoginDisposalResult(
                  uuid(1), uuid(2), uuid(3), Disposition.NO_TOKEN, ProofKind.NO_ISSUANCE_BINDING);
      case "partial" -> fixture.columns.put("verified_token_sha256", "a".repeat(64));
      case "unknown-wire" ->
          fixture.columns.put("retirement_request_bytes", new byte[] {(byte) 0xA0, 6, 1});
      default -> throw new AssertionError(change);
    }
    writableTransaction();
    var changedReceipt = receipt;
    assertThatThrownBy(() -> fixture.journal.recordDisposalReceipt(ORIGINAL, changedReceipt))
        .isInstanceOf(IllegalStateException.class);
    verify(fixture.dsl, never()).execute(anyString(), any(Object[].class));
  }

  private static void writableTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
        Connection.TRANSACTION_READ_COMMITTED);
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
  }

  private static final class RowFixture {
    private final Map<String, Object> columns = new HashMap<>();
    private final DSLContext dsl = mock(DSLContext.class);
    private final InitialGameplayLoginCoordinationJournalRepository journal =
        new InitialGameplayLoginCoordinationJournalRepository(dsl);

    @SuppressWarnings("unchecked")
    private RowFixture() {
      columns.put("request_id", ORIGINAL.requestId());
      columns.put("caller_context_id", ORIGINAL.callerContextId());
      columns.put("expected_attempt_fence", ORIGINAL.expectedAttemptFence());
      columns.put("abandoned", false);
      Record row = mock(Record.class);
      when(row.get(anyString(), any(Class.class)))
          .thenAnswer(invocation -> columns.get(invocation.getArgument(0)));
      when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
      // Connection qualification is deliberately not simulated as physical PostgreSQL proof.
      when(dsl.connectionResult(any())).thenReturn(null);
    }
  }
}
