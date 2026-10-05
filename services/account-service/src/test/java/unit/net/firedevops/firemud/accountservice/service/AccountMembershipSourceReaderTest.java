package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto.MembershipBaseline;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipSourceReaderTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("6d39b28e-3ef4-44a7-8479-19672a9663c4");
  private static final UUID TENANT_ID = UUID.fromString("a039b28e-3ef4-44a7-8479-19672a9663c4");

  @Test
  void readsStrictMembershipSnapshotAndIndependentSourceCountersInOwnedTransaction() {
    AccountMembershipAuthorityEventProducer membershipProducer =
        mock(AccountMembershipAuthorityEventProducer.class);
    AccountAuthorityGenerationRepository generationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    PlatformTransactionManager transactionManager = transactionManager();
    RuntimeMembershipSnapshotDto membership = membershipSnapshot(ACCOUNT_ID, TENANT_ID, "8", "3");
    ScopeState sourceState =
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            3L,
            9L,
            new IssuanceFence(ACCOUNT_ID, 2L, 11L));
    when(membershipProducer.readExistingRuntimeMembershipSnapshot(ACCOUNT_ID, TENANT_ID))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return membership;
            });
    when(generationRepository.read(AuthorityScope.membership(ACCOUNT_ID, TENANT_ID)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return sourceState;
            });

    AccountMembershipSourceReader reader =
        new AccountMembershipSourceReader(
            membershipProducer, generationRepository, transactionManager);

    AccountMembershipSourceReader.MembershipSourceSnapshot result =
        reader.readCurrent(ACCOUNT_ID, TENANT_ID);

    assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(result.tenantId()).isEqualTo(TENANT_ID);
    assertThat(result.snapshot()).isSameAs(membership);
    assertThat(result.snapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(TENANT_ID.toString(), "8"));
    assertThat(result.sourceState().generation()).isEqualTo(3L);
    assertThat(result.sourceState().sourceVersion()).isEqualTo(9L);
    assertThat(result.sourceState().issuanceFence().sourceVersion()).isEqualTo(11L);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    verify(membershipProducer).readExistingRuntimeMembershipSnapshot(ACCOUNT_ID, TENANT_ID);
    verify(generationRepository).read(AuthorityScope.membership(ACCOUNT_ID, TENANT_ID));
  }

  @Test
  void rejectsAmbientTransactionBeforeCallingOwnerCollaborators() {
    AccountMembershipAuthorityEventProducer membershipProducer =
        mock(AccountMembershipAuthorityEventProducer.class);
    AccountAuthorityGenerationRepository generationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    AccountMembershipSourceReader reader =
        new AccountMembershipSourceReader(
            membershipProducer, generationRepository, transactionManager);
    boolean previouslyActive = TransactionSynchronizationManager.isActualTransactionActive();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    try {
      assertThatThrownBy(() -> reader.readCurrent(ACCOUNT_ID, TENANT_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("cannot join an ambient transaction");
      verifyNoInteractions(membershipProducer, generationRepository, transactionManager);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(previouslyActive);
    }
  }

  @Test
  void rejectsNilIdentityBeforeOpeningTransactionOrReadingSource() {
    AccountMembershipAuthorityEventProducer membershipProducer =
        mock(AccountMembershipAuthorityEventProducer.class);
    AccountAuthorityGenerationRepository generationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    AccountMembershipSourceReader reader =
        new AccountMembershipSourceReader(
            membershipProducer, generationRepository, transactionManager);

    assertThatThrownBy(() -> reader.readCurrent(new UUID(0L, 0L), TENANT_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil UUID");
    verifyNoInteractions(membershipProducer, generationRepository, transactionManager);
  }

  @Test
  void rejectsSourceStateForDifferentAccountTenantPair() {
    RuntimeMembershipSnapshotDto membership = membershipSnapshot(ACCOUNT_ID, TENANT_ID, "8", "3");
    ScopeState sourceState =
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, UUID.randomUUID()),
            3L,
            9L,
            new IssuanceFence(ACCOUNT_ID, 2L, 11L));

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, membership, sourceState))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact scope");
  }

  @Test
  void rejectsMembershipGenerationThatDiffersFromTheOneTenantSnapshot() {
    RuntimeMembershipSnapshotDto membership = membershipSnapshot(ACCOUNT_ID, TENANT_ID, "8", "4");
    ScopeState sourceState =
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            3L,
            9L,
            new IssuanceFence(ACCOUNT_ID, 2L, 11L));

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, membership, sourceState))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version map or generation");
  }

  @Test
  void rejectsSnapshotWithAnotherCanonicalIdentityOrMembershipVersionKey() {
    RuntimeMembershipSnapshotDto wrongIdentity =
        membershipSnapshot(UUID.randomUUID(), TENANT_ID, "8", "3");
    ScopeState sourceState =
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            3L,
            9L,
            new IssuanceFence(ACCOUNT_ID, 2L, 11L));

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, wrongIdentity, sourceState))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Account/tenant source scope");

    RuntimeMembershipSnapshotDto wrongVersionKey = mock(RuntimeMembershipSnapshotDto.class);
    when(wrongVersionKey.accountUuid()).thenReturn(ACCOUNT_ID.toString());
    when(wrongVersionKey.tenantUuid()).thenReturn(TENANT_ID.toString());
    when(wrongVersionKey.requestAccountUuid()).thenReturn(ACCOUNT_ID.toString());
    when(wrongVersionKey.requestTenantUuid()).thenReturn(TENANT_ID.toString());
    when(wrongVersionKey.issuanceFence()).thenReturn("2");
    when(wrongVersionKey.membershipBaseline())
        .thenReturn(
            new MembershipBaseline("ACTIVE", Map.of(UUID.randomUUID().toString(), "8"), "3"));

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, wrongVersionKey, sourceState))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version map or generation");
  }

  @Test
  void rejectsIssuanceFenceFromAnotherAccountOrSnapshotFenceValue() {
    RuntimeMembershipSnapshotDto membership = membershipSnapshot(ACCOUNT_ID, TENANT_ID, "8", "3");
    ScopeState wrongAccountFence =
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            3L,
            9L,
            new IssuanceFence(UUID.randomUUID(), 2L, 11L));

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, membership, wrongAccountFence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuance fence");

    RuntimeMembershipSnapshotDto wrongFenceValue = mock(RuntimeMembershipSnapshotDto.class);
    when(wrongFenceValue.accountUuid()).thenReturn(ACCOUNT_ID.toString());
    when(wrongFenceValue.tenantUuid()).thenReturn(TENANT_ID.toString());
    when(wrongFenceValue.requestAccountUuid()).thenReturn(ACCOUNT_ID.toString());
    when(wrongFenceValue.requestTenantUuid()).thenReturn(TENANT_ID.toString());
    when(wrongFenceValue.issuanceFence()).thenReturn("3");
    when(wrongFenceValue.membershipBaseline())
        .thenReturn(new MembershipBaseline("ACTIVE", Map.of(TENANT_ID.toString(), "8"), "3"));
    ScopeState matchingScope =
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            3L,
            9L,
            new IssuanceFence(ACCOUNT_ID, 2L, 11L));

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, wrongFenceValue, matchingScope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuance fence");
  }

  @Test
  void rejectsMissingAccountIssuanceFence() {
    RuntimeMembershipSnapshotDto membership = membershipSnapshot(ACCOUNT_ID, TENANT_ID, "8", "3");
    ScopeState sourceState =
        new ScopeState(AuthorityScope.membership(ACCOUNT_ID, TENANT_ID), 3L, 9L, null);

    assertThatThrownBy(
            () ->
                new AccountMembershipSourceReader.MembershipSourceSnapshot(
                    ACCOUNT_ID, TENANT_ID, membership, sourceState))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Account issuance fence");
  }

  private static RuntimeMembershipSnapshotDto membershipSnapshot(
      UUID accountId, UUID tenantId, String membershipVersion, String membershipGeneration) {
    RuntimeMembershipSnapshotDto snapshot = mock(RuntimeMembershipSnapshotDto.class);
    when(snapshot.accountUuid()).thenReturn(accountId.toString());
    when(snapshot.tenantUuid()).thenReturn(tenantId.toString());
    when(snapshot.requestAccountUuid()).thenReturn(accountId.toString());
    when(snapshot.requestTenantUuid()).thenReturn(tenantId.toString());
    when(snapshot.issuanceFence()).thenReturn("2");
    when(snapshot.membershipBaseline())
        .thenReturn(
            new MembershipBaseline(
                "ACTIVE", Map.of(tenantId.toString(), membershipVersion), membershipGeneration));
    return snapshot;
  }

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transactionStatus = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(
            invocation -> {
              TransactionDefinition definition = invocation.getArgument(0);
              assertThat(definition.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(definition.isReadOnly()).isFalse();
              TransactionSynchronizationManager.setActualTransactionActive(true);
              return transactionStatus;
            });
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .commit(transactionStatus);
    doAnswer(
            invocation -> {
              TransactionSynchronizationManager.setActualTransactionActive(false);
              return null;
            })
        .when(transactionManager)
        .rollback(transactionStatus);
    return transactionManager;
  }
}
