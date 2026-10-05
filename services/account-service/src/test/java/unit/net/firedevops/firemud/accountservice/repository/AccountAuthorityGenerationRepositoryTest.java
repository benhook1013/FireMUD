package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AccountAuthorityGenerationRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountAuthorityGenerationRepository repository =
      new AccountAuthorityGenerationRepository(dsl);

  @Test
  void scopeIdentityPreservesExactIssuerAndUuidPairs() {
    UUID accountId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID anotherAccountId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    UUID tenantId = UUID.fromString("33333333-3333-4333-8333-333333333333");

    assertThat(AuthorityScope.issuer("Issuer-A").issuerId()).isEqualTo("Issuer-A");
    assertThat(AuthorityScope.issuer("Issuer-A")).isNotEqualTo(AuthorityScope.issuer("issuer-a"));
    assertThat(AuthorityScope.membership(accountId, tenantId))
        .isNotEqualTo(AuthorityScope.membership(anotherAccountId, tenantId));
    assertThat(AuthorityScope.membership(accountId, tenantId).kind())
        .isEqualTo(ScopeKind.MEMBERSHIP);
  }

  @Test
  void malformedScopesAndNonPositiveStateFailBeforeStorageAccess() {
    UUID accountId = UUID.fromString("11111111-1111-4111-8111-111111111111");

    assertThatThrownBy(() -> AuthorityScope.issuer("  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuthorityScope.account(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ScopeState(
                    AuthorityScope.account(accountId),
                    0L,
                    1L,
                    new IssuanceFence(accountId, 1L, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IssuanceFence(accountId, 0L, 1L))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void storageOperationsRequireTheCallersTransaction() throws ReflectiveOperationException {
    assertMandatory(
        AccountAuthorityGenerationRepository.class.getMethod("initialize", AuthorityScope.class));
    assertMandatory(
        AccountAuthorityGenerationRepository.class.getMethod(
            "initializeIssuerIfAbsent", String.class));
    assertMandatory(
        AccountAuthorityGenerationRepository.class.getMethod(
            "initializeTenantIfAbsent", UUID.class));
    assertMandatory(
        AccountAuthorityGenerationRepository.class.getMethod("read", AuthorityScope.class));
    assertMandatory(
        AccountAuthorityGenerationRepository.class.getMethod(
            "advance", ScopeState.class, IssuanceFence.class));
    assertMandatory(
        AccountAuthorityGenerationRepository.class.getMethod(
            "readCompositeSnapshot",
            String.class,
            UUID.class,
            java.util.Collection.class,
            java.util.Collection.class));
  }

  @Test
  void mismatchedFenceAndScopeStateAreDeniedWithoutDatabaseAccess() {
    UUID accountId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID otherAccountId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    IssuanceFence fence = new IssuanceFence(accountId, 1L, 1L);
    ScopeState state = new ScopeState(AuthorityScope.account(accountId), 1L, 1L, fence);

    assertThatThrownBy(() -> repository.advance(state, new IssuanceFence(otherAccountId, 1L, 1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.advance(state, null))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void issuerEnrollmentRejectsBlankIssuerBeforeStorageAccess() {
    assertThatThrownBy(() -> repository.initializeIssuerIfAbsent("  "))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void tenantEnrollmentRejectsMissingCanonicalTenantIdentityBeforeStorageAccess() {
    assertThatThrownBy(() -> repository.initializeTenantIfAbsent(null))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  @Test
  void snapshotRequiresEveryExplicitScopeWithoutFillingInMissingAuthority() {
    UUID accountId = UUID.fromString("11111111-1111-4111-8111-111111111111");

    assertThatThrownBy(
            () ->
                repository.readCompositeSnapshot("issuer-A", accountId, null, java.util.List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.readCompositeSnapshot(
                    "issuer-A",
                    accountId,
                    java.util.List.of(),
                    java.util.Arrays.asList(accountId, null)))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(dsl);
  }

  private void assertMandatory(java.lang.reflect.Method method) {
    Transactional annotation = method.getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
