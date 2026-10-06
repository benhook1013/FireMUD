package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountJoinOperationRepositoryTest {
  @Test
  void canonicalIntentAndPolicyMethodsRequireOwnerTransactionBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountConnectScopeRepository connectScopes = mock(AccountConnectScopeRepository.class);
    AccountJoinOperationRepository repository =
        new AccountJoinOperationRepository(dsl, connectScopes);
    CanonicalJoinScopeV2 scope = scope();
    boolean priorState = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    try {
      assertThatThrownBy(() -> repository.insertCanonicalIntent("request-v2", scope, "caller"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account JOIN operation access requires an active owner transaction");
      assertThatThrownBy(() -> repository.findCanonicalEvidenceByRequestId("request-v2"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account JOIN operation access requires an active owner transaction");
      assertThatThrownBy(() -> repository.findCanonicalEvidenceForUpdateByRequestId("request-v2"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account JOIN operation access requires an active owner transaction");
      assertThatThrownBy(
              () -> repository.bindCanonicalPolicyEvidence("request-v2", scope, "caller", true, 1L))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account JOIN operation access requires an active owner transaction");
      assertThatThrownBy(
              () ->
                  repository.recordCanonicalPolicyUnavailable(
                      "request-v2", scope, "caller", "ENTITLEMENT_UNAVAILABLE"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account JOIN operation access requires an active owner transaction");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(priorState);
    }

    verifyNoInteractions(dsl, connectScopes);
  }

  @Test
  void canonicalOperationForUpdateRequiresWritableOwnerTransactionBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountConnectScopeRepository connectScopes = mock(AccountConnectScopeRepository.class);
    AccountJoinOperationRepository repository =
        new AccountJoinOperationRepository(dsl, connectScopes);
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.findCanonicalEvidenceForUpdateByRequestId("request-v2"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account JOIN operation write requires a writable owner transaction");
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
    }
    verifyNoInteractions(dsl, connectScopes);
  }

  private static CanonicalJoinScopeV2 scope() {
    return new CanonicalJoinScopeV2(
        "opaque-canonical-scope-token",
        UUID.fromString("46775955-2c40-42b9-bb7f-30b52ba4d4e5"),
        UUID.fromString("58f72bb3-a1c9-49ec-84de-08a64c2bdfc7"),
        UUID.fromString("e3c431d9-52e9-4b50-8aa1-ecb0400ee884"),
        "tenant",
        "world",
        "production",
        UUID.fromString("1cc10d35-a1f8-4ac7-9a51-a630bb02a8ad"),
        "SHARED",
        UUID.fromString("c69bbd57-5105-44fb-934f-90cf00fc3bb4"),
        1L,
        1L,
        "2026-10-03T00:00:00Z",
        "2026-10-03T00:01:00Z");
  }
}
