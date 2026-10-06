package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountConnectScopeRepositoryTest {
  @Test
  void canonicalMethodsRequireOwnerTransactionBeforeSqlOrSourceReads() {
    DSLContext dsl = mock(DSLContext.class);
    AccountTenantIdentityResolver retainedTenants = mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshTenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountConnectScopeRepository repository =
        new AccountConnectScopeRepository(dsl, retainedTenants, freshTenants);
    CanonicalJoinScopeV2 scope = scope();
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            42L,
            TenantProvenanceKind.APPROVED_RETAINED,
            UUID.fromString("9e0f2e18-84ab-4f7e-a6cf-7ebd3e39bd81"),
            "sha256:" + "a".repeat(64));

    assertThatThrownBy(() -> repository.insertCanonical(17L, scope, provenance))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical Account connect scope access requires an active owner transaction");
    assertThatThrownBy(() -> repository.findCanonical(scope.connectScopeId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical Account connect scope access requires an active owner transaction");
    assertThatThrownBy(
            () -> repository.findCanonicalEvidenceByTokenHash("sha256:" + "b".repeat(64)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Canonical Account connect scope access requires an active owner transaction");

    verifyNoInteractions(dsl, retainedTenants, freshTenants);
  }

  @Test
  void canonicalLockTakingEvidenceReadRejectsReadOnlyTransactionBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountTenantIdentityResolver retainedTenants = mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshTenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountConnectScopeRepository repository =
        new AccountConnectScopeRepository(dsl, retainedTenants, freshTenants);
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(
              () -> repository.findCanonicalEvidenceByTokenHash("sha256:" + "b".repeat(64)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account connect scope write requires a writable owner transaction");
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
    }
    verifyNoInteractions(dsl, retainedTenants, freshTenants);
  }

  @Test
  void canonicalScopeReadRejectsReadOnlyTransactionBeforeDependenciesOrSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountTenantIdentityResolver retainedTenants = mock(AccountTenantIdentityResolver.class);
    FreshTenantIdentityAssociationRepository freshTenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountConnectScopeRepository repository =
        new AccountConnectScopeRepository(dsl, retainedTenants, freshTenants);
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(() -> repository.findCanonical("opaque-scope-token"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(
              "Canonical Account connect scope write requires a writable owner transaction");
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
    }
    verifyNoInteractions(dsl, retainedTenants, freshTenants);
  }

  private static CanonicalJoinScopeV2 scope() {
    return new CanonicalJoinScopeV2(
        "opaque-scope-token",
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
