package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository.TokenFenceUnavailableException;
import net.firedevops.firemud.accountservice.repository.AccountGameplayTokenIdentityFenceRepository.TokenRevokedException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountGameplayTokenIdentityFenceRepositoryTest {
  private static final TokenIdentity IDENTITY =
      new TokenIdentity(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "a".repeat(64),
          UUID.randomUUID(),
          1_800_000_000L,
          1L,
          11L);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void registrationIsNotAPublicAdoptionApi() throws Exception {
    var method =
        AccountGameplayTokenIdentityFenceRepository.class.getDeclaredMethod(
            "registerCommittedIssuance", TokenIdentity.class);
    assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
    assertThat(Modifier.isProtected(method.getModifiers())).isFalse();
  }

  @Test
  void writableAccountTransactionRequiredBeforeAnyRead() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountGameplayTokenIdentityFenceRepository(dsl);
    assertThatThrownBy(() -> repository.requireActiveForUpdate(IDENTITY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account transaction");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> repository.requireActiveForUpdate(IDENTITY))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void activeReadLocksAccountBeforeTokenAndIndependentlyReadsExactCommittedSource() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    Record source = mock(Record.class);
    Record token = tokenRow(State.ACTIVE);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, token, source);
    transaction();
    var fence =
        new AccountGameplayTokenIdentityFenceRepository(dsl).requireActiveForUpdate(IDENTITY);
    assertThat(fence.identity()).isEqualTo(IDENTITY);
    assertThat(fence.tokenIdentityFence()).isEqualTo(1L);
    var ordered = inOrder(dsl);
    ordered
        .verify(dsl)
        .fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
            IDENTITY.accountId());
    ordered
        .verify(dsl)
        .fetchOne(
            "SELECT account_uuid, operation_id, issuance_request_id, token_hash, token_jti, "
                + "not_before_epoch_second, token_generation, issuance_fence, token_identity_fence, "
                + "state, revocation_request_id, revocation_digest "
                + "FROM account_gameplay_token_identity_fences WHERE operation_id = ? FOR UPDATE",
            IDENTITY.operationId());
    ordered
        .verify(dsl)
        .fetchOne(
            "SELECT operation_id FROM account_gameplay_delegation_issuance_operations "
                + "WHERE operation_id = ? AND request_id = ? AND account_uuid = ? AND token_hash = ? "
                + "AND token_jti = ? AND not_before_epoch_second = ? AND token_generation = ? "
                + "AND issuance_fence = ? AND status = 'COMMITTED'",
            IDENTITY.operationId(),
            IDENTITY.issuanceRequestId(),
            IDENTITY.accountId(),
            IDENTITY.tokenSha256(),
            IDENTITY.tokenJti(),
            IDENTITY.notBeforeEpochSecond(),
            IDENTITY.tokenGeneration(),
            IDENTITY.issuanceFence());
  }

  @Test
  void missingCommittedSourceCannotAuthorizeEvenWithActiveTokenRow() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    Record token = tokenRow(State.ACTIVE);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, token, null);
    transaction();
    assertThatThrownBy(
            () ->
                new AccountGameplayTokenIdentityFenceRepository(dsl)
                    .requireActiveForUpdate(IDENTITY))
        .isInstanceOf(TokenFenceUnavailableException.class);
  }

  @Test
  void exactIdentityMismatchCannotUseAnotherActiveFence() {
    DSLContext dsl = mock(DSLContext.class);
    Record account = mock(Record.class);
    Record token = tokenRow(State.ACTIVE);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, token);
    transaction();
    TokenIdentity changedNbf =
        new TokenIdentity(
            IDENTITY.accountId(),
            IDENTITY.operationId(),
            IDENTITY.issuanceRequestId(),
            IDENTITY.tokenSha256(),
            IDENTITY.tokenJti(),
            IDENTITY.notBeforeEpochSecond() + 1L,
            IDENTITY.tokenGeneration(),
            IDENTITY.issuanceFence());
    assertThatThrownBy(
            () ->
                new AccountGameplayTokenIdentityFenceRepository(dsl)
                    .requireActiveForUpdate(changedNbf))
        .isInstanceOf(TokenFenceUnavailableException.class);
  }

  @Test
  void pendingAndCommittedRevocationBothDenyAdmission() {
    for (State state : new State[] {State.PENDING, State.COMMITTED}) {
      DSLContext dsl = mock(DSLContext.class);
      Record account = mock(Record.class);
      Record token = tokenRow(state);
      Record source = mock(Record.class);
      when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(account, token, source);
      transaction();
      assertThatThrownBy(
              () ->
                  new AccountGameplayTokenIdentityFenceRepository(dsl)
                      .requireActiveForUpdate(IDENTITY))
          .isInstanceOf(TokenRevokedException.class);
    }
  }

  @Test
  void revocationDigestBindsEveryExactTokenIdentityAndRequestField() {
    UUID requestId = UUID.randomUUID();
    String digest =
        AccountGameplayTokenIdentityFenceRepository.revocationDigest(IDENTITY, requestId);
    TokenIdentity[] changed = {
      new TokenIdentity(
          UUID.randomUUID(),
          IDENTITY.operationId(),
          IDENTITY.issuanceRequestId(),
          IDENTITY.tokenSha256(),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond(),
          1L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          UUID.randomUUID(),
          IDENTITY.issuanceRequestId(),
          IDENTITY.tokenSha256(),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond(),
          1L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          IDENTITY.operationId(),
          UUID.randomUUID(),
          IDENTITY.tokenSha256(),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond(),
          1L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          IDENTITY.operationId(),
          IDENTITY.issuanceRequestId(),
          "b".repeat(64),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond(),
          1L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          IDENTITY.operationId(),
          IDENTITY.issuanceRequestId(),
          IDENTITY.tokenSha256(),
          UUID.randomUUID(),
          IDENTITY.notBeforeEpochSecond(),
          1L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          IDENTITY.operationId(),
          IDENTITY.issuanceRequestId(),
          IDENTITY.tokenSha256(),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond() + 1L,
          1L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          IDENTITY.operationId(),
          IDENTITY.issuanceRequestId(),
          IDENTITY.tokenSha256(),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond(),
          2L,
          11L),
      new TokenIdentity(
          IDENTITY.accountId(),
          IDENTITY.operationId(),
          IDENTITY.issuanceRequestId(),
          IDENTITY.tokenSha256(),
          IDENTITY.tokenJti(),
          IDENTITY.notBeforeEpochSecond(),
          1L,
          12L)
    };
    for (TokenIdentity identity : changed) {
      assertThat(AccountGameplayTokenIdentityFenceRepository.revocationDigest(identity, requestId))
          .isNotEqualTo(digest);
    }
    assertThat(
            AccountGameplayTokenIdentityFenceRepository.revocationDigest(
                IDENTITY, UUID.randomUUID()))
        .isNotEqualTo(digest);
    assertThat(IDENTITY.toString())
        .doesNotContain(IDENTITY.tokenSha256(), IDENTITY.tokenJti().toString());
  }

  @Test
  void callerClaimedDigestCannotStartRevocationOrAcquireLocks() {
    DSLContext dsl = mock(DSLContext.class);
    transaction();
    assertThatThrownBy(
            () ->
                new AccountGameplayTokenIdentityFenceRepository(dsl)
                    .beginRevocationIntent(IDENTITY, UUID.randomUUID(), "b".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  private static Record tokenRow(State state) {
    Record row = mock(Record.class);
    when(row.get("account_uuid", UUID.class)).thenReturn(IDENTITY.accountId());
    when(row.get("operation_id", UUID.class)).thenReturn(IDENTITY.operationId());
    when(row.get("issuance_request_id", UUID.class)).thenReturn(IDENTITY.issuanceRequestId());
    when(row.get("token_hash", String.class)).thenReturn(IDENTITY.tokenSha256());
    when(row.get("token_jti", UUID.class)).thenReturn(IDENTITY.tokenJti());
    when(row.get("not_before_epoch_second", Long.class))
        .thenReturn(IDENTITY.notBeforeEpochSecond());
    when(row.get("token_generation", Long.class)).thenReturn(IDENTITY.tokenGeneration());
    when(row.get("issuance_fence", Long.class)).thenReturn(IDENTITY.issuanceFence());
    when(row.get("state", String.class)).thenReturn(state.name());
    when(row.get("token_identity_fence", Long.class))
        .thenReturn(state == State.ACTIVE ? 1L : state == State.PENDING ? 2L : 3L);
    if (state != State.ACTIVE) {
      when(row.get("revocation_request_id", UUID.class)).thenReturn(UUID.randomUUID());
      when(row.get("revocation_digest", String.class)).thenReturn("b".repeat(64));
    }
    return row;
  }

  private static void transaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }
}
