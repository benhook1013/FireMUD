package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantMembershipRepositoryTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CREATION_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void absentFreshMembershipIsNonEnrollingAndDoesNotChangePairAuthority() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    Account account = new Account();
    account.setId(17L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    account.setAccountUuidSourceNumericId(17L);
    when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(tenants.read(TENANT_UUID)).thenReturn(Optional.of(freshTenantEvidence()));
    doReturn(null).when(dsl).fetchOne(anyString(), any(Object[].class));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    assertThat(repository.findFreshMembership(ACCOUNT_UUID, TENANT_UUID)).isEmpty();
    verify(accounts).findByAccountUuid(ACCOUNT_UUID);
    verify(tenants).read(TENANT_UUID);
    verify(pairs).readState(ACCOUNT_UUID, TENANT_UUID);
    verify(pairs, org.mockito.Mockito.never())
        .enrollAbsence(any(UUID.class), any(UUID.class), any());
    verify(pairs, org.mockito.Mockito.never()).commitTransition(any(), any());
  }

  @Test
  void freshMembershipReadRejectsTenantEvidenceForAnotherCanonicalUuid() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    Account account = canonicalAccount();
    UUID otherTenantUuid = UUID.fromString("55555555-5555-4555-8555-555555555555");
    when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(tenants.read(TENANT_UUID)).thenReturn(Optional.of(freshTenantEvidence(otherTenantUuid)));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(
            dsl, accounts, tenants, mock(AccountMembershipPairAuthorityRepository.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.findFreshMembership(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("differs from the requested canonical UUID");
    verifyNoInteractions(dsl);
  }

  @Test
  void freshMembershipReadRequiresOwnerTransactionBeforeAnyLookup() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.findFreshMembership(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(dsl, accounts, tenants, pairs);
  }

  @Test
  void freshMembershipWriteReadRequiresWritableOwnerTransactionBeforeAnyLookup() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.findFreshMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(dsl, accounts, tenants, pairs);
  }

  @Test
  void firstFreshJoinPreparesMembershipWithoutAdvancingPairAuthority() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    Account account = canonicalAccount();
    when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(account));
    when(tenants.read(TENANT_UUID)).thenReturn(Optional.of(freshTenantEvidence()));
    PairAuthority baseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 1L, 1L, 0L, null, null, false);
    when(pairs.readForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(Optional.empty(), Optional.of(baseline));
    when(pairs.enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance())).thenReturn(baseline);

    Record lockedAccount = mock(Record.class);
    when(lockedAccount.get("id", Long.class)).thenReturn(17L);
    when(lockedAccount.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_UUID);
    when(lockedAccount.get("account_uuid_provenance", String.class))
        .thenReturn(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION.name());
    when(lockedAccount.get("account_uuid_source_numeric_id", Long.class)).thenReturn(17L);
    Record membershipReadback = freshMembershipRecord(73L, 17L, 2L, 1L);
    AtomicInteger membershipReads = new AtomicInteger();
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM accounts WHERE id = ? FOR UPDATE")) {
                return lockedAccount;
              }
              if (sql.contains("FROM account_tenant_membership")) {
                return membershipReads.getAndIncrement() == 0 ? null : membershipReadback;
              }
              return null;
            });
    ResultQuery<?> insertQuery = mock(ResultQuery.class);
    doReturn(insertQuery).when(dsl).resultQuery(anyString(), any(Object[].class));
    doReturn(73L).when(insertQuery).fetchOne(0, Long.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    AccountTenantMembership result =
        repository.createFreshMembershipForJoin(ACCOUNT_UUID, TENANT_UUID);

    assertThat(result.getId()).isEqualTo(73L);
    assertThat(result.getTenantId()).isNull();
    assertThat(result.getTenantUuid()).isEqualTo(TENANT_UUID);
    assertThat(result.getMembershipVersion()).isEqualTo(2L);
    assertThat(result.getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(result.getLifecycleState()).isEqualTo("ACTIVE");
    assertThat(result.getAuthorityProvenance()).isEqualTo("EXPLICIT_JOIN");
    assertThat(result.isGameplayAdmissionAllowed()).isTrue();
    verify(pairs).enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance());
    verify(pairs, org.mockito.Mockito.never()).commitTransition(any(), any());
    var order = inOrder(accounts, dsl, tenants, pairs);
    order.verify(accounts).findByAccountUuid(ACCOUNT_UUID);
    order
        .verify(dsl)
        .fetchOne(contains("FROM accounts WHERE id = ? FOR UPDATE"), any(Object[].class));
    order.verify(tenants).read(TENANT_UUID);
    order.verify(dsl).fetchOne(contains("FROM account_tenant_membership"), any(Object[].class));
    order.verify(pairs).readForUpdate(ACCOUNT_UUID, TENANT_UUID);
    order.verify(pairs).enrollAbsence(ACCOUNT_UUID, TENANT_UUID, freshProvenance());
    order
        .verify(dsl)
        .resultQuery(contains("INSERT INTO account_tenant_membership"), any(Object[].class));
    order.verify(dsl).fetchOne(contains("FROM account_tenant_membership"), any(Object[].class));
    order.verify(pairs).readForUpdate(ACCOUNT_UUID, TENANT_UUID);
  }

  @Test
  void firstFreshJoinRejectsNilTenantBeforeTakingAnyLocks() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.createFreshMembershipForJoin(ACCOUNT_UUID, new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-nil canonical UUID");
    verifyNoInteractions(dsl, accounts, tenants, pairs);
  }

  @Test
  void firstFreshJoinRejectsExistingMembershipWithoutReactivatingOrWritingPairState() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(canonicalAccount()));
    when(tenants.read(TENANT_UUID)).thenReturn(Optional.of(freshTenantEvidence()));
    Record lockedAccount = mock(Record.class);
    when(lockedAccount.get("id", Long.class)).thenReturn(17L);
    when(lockedAccount.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_UUID);
    when(lockedAccount.get("account_uuid_provenance", String.class))
        .thenReturn(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION.name());
    when(lockedAccount.get("account_uuid_source_numeric_id", Long.class)).thenReturn(17L);
    Record existingMembership = freshMembershipRecord(73L, 17L, 2L, 1L);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              return sql.contains("FROM accounts WHERE id = ? FOR UPDATE")
                  ? lockedAccount
                  : existingMembership;
            });
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.createFreshMembershipForJoin(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot overwrite an existing canonical membership row");
    verify(dsl, org.mockito.Mockito.never())
        .resultQuery(contains("INSERT INTO account_tenant_membership"), any(Object[].class));
    verifyNoInteractions(pairs);
  }

  @Test
  void firstJoinPublicationReadCanSeePendingMembershipButLeavesItNonAuthoritative() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(canonicalAccount()));
    when(tenants.read(TENANT_UUID)).thenReturn(Optional.of(freshTenantEvidence()));
    Record lockedAccount = lockedAccountRecord();
    Record pendingMembership = freshMembershipRecord(73L, 17L, 2L, 1L);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation ->
                ((String) invocation.getArgument(0))
                        .contains("FROM accounts WHERE id = ? FOR UPDATE")
                    ? lockedAccount
                    : pendingMembership);
    PairAuthority baseline =
        new PairAuthority(
            ACCOUNT_UUID, TENANT_UUID, freshProvenance(), false, 1L, 1L, 0L, null, null, false);
    when(pairs.readForUpdate(ACCOUNT_UUID, TENANT_UUID)).thenReturn(Optional.of(baseline));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    AccountTenantMembership result =
        repository.findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID).orElseThrow();

    assertThat(result.getMembershipVersion()).isEqualTo(2L);
    assertThat(result.getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(result.isGameplayAdmissionAllowed()).isTrue();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.findFreshMembershipForUpdate(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Positive Account membership pair is absent");
    verify(pairs, org.mockito.Mockito.times(2)).readForUpdate(ACCOUNT_UUID, TENANT_UUID);
    verify(pairs, org.mockito.Mockito.never()).readPositive(ACCOUNT_UUID, TENANT_UUID);
    verify(pairs, org.mockito.Mockito.never()).commitTransition(any(), any());
    verify(dsl, org.mockito.Mockito.never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void firstJoinPublicationReadAllowsOnlyTheExactPositiveFirstEventForReplay() {
    DSLContext dsl = mock(DSLContext.class);
    AccountRepository accounts = mock(AccountRepository.class);
    FreshTenantIdentityAssociationRepository tenants =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountMembershipPairAuthorityRepository pairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    when(accounts.findByAccountUuid(ACCOUNT_UUID)).thenReturn(Optional.of(canonicalAccount()));
    when(tenants.read(TENANT_UUID)).thenReturn(Optional.of(freshTenantEvidence()));
    Record lockedAccount = lockedAccountRecord();
    Record positiveMembership = freshMembershipRecord(73L, 17L, 2L, 1L);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation ->
                ((String) invocation.getArgument(0))
                        .contains("FROM accounts WHERE id = ? FOR UPDATE")
                    ? lockedAccount
                    : positiveMembership);
    PairAuthority positive =
        new PairAuthority(
            ACCOUNT_UUID,
            TENANT_UUID,
            freshProvenance(),
            true,
            2L,
            1L,
            1L,
            "event-1",
            "sha256:" + "b".repeat(64),
            false);
    when(pairs.readForUpdate(ACCOUNT_UUID, TENANT_UUID)).thenReturn(Optional.of(positive));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(dsl, accounts, tenants, pairs);

    AccountTenantMembership result =
        repository.findFreshJoinForPublicationForUpdate(ACCOUNT_UUID, TENANT_UUID).orElseThrow();

    assertThat(result.getMembershipVersion()).isEqualTo(2L);
    assertThat(result.getMembershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(result.getLifecycleState()).isEqualTo("ACTIVE");
    assertThat(result.isGameplayAdmissionAllowed()).isTrue();
    verify(pairs).readForUpdate(ACCOUNT_UUID, TENANT_UUID);
    verify(pairs, org.mockito.Mockito.never()).commitTransition(any(), any());
  }

  @Test
  void numericMembershipSelectorsRejectNullTenantInsteadOfMatchingFreshRows() {
    DSLContext dsl = mock(DSLContext.class);
    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(
            dsl,
            mock(AccountRepository.class),
            mock(FreshTenantIdentityAssociationRepository.class),
            mock(AccountMembershipPairAuthorityRepository.class));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.findByAccountIdAndTenantId(17L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive tenant ID");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.existsByAccountIdAndTenantId(17L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive tenant ID");
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> repository.deleteByAccountIdAndTenantId(17L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive tenant ID");
    for (long invalidTenantId : new long[] {0L, -1L}) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> repository.findByAccountIdAndTenantId(17L, invalidTenantId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive tenant ID");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> repository.existsByAccountIdAndTenantId(17L, invalidTenantId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive tenant ID");
    }
    verifyNoInteractions(dsl);
  }

  @Test
  void numericMembershipWriterCannotAliasFreshCanonicalTenantIdentity() {
    DSLContext dsl = mock(DSLContext.class);
    AccountTenantMembershipRepository repository =
        new AccountTenantMembershipRepository(
            dsl,
            mock(AccountRepository.class),
            mock(FreshTenantIdentityAssociationRepository.class),
            mock(AccountMembershipPairAuthorityRepository.class));
    AccountTenantMembership freshMembership = new AccountTenantMembership();
    freshMembership.setTenantId(42L);
    freshMembership.setTenantUuid(TENANT_UUID);
    freshMembership.setTenantProvenanceKind("FRESH_GAME_DESIGN");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.save(freshMembership))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("existing numeric retained-tenant contract");
    verifyNoInteractions(dsl);
  }

  private static FreshTenantCreationEvidence freshTenantEvidence() {
    return freshTenantEvidence(TENANT_UUID);
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        CREATION_REQUEST_ID,
        OPERATION_ID,
        REQUEST_DIGEST,
        tenantUuid,
        42L,
        "game-tenant-42",
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            CREATION_REQUEST_ID,
            OPERATION_ID,
            REQUEST_DIGEST,
            tenantUuid,
            42L,
            "game-tenant-42",
            "NEW_GAME_ROW"));
  }

  private static Account canonicalAccount() {
    Account account = new Account();
    account.setId(17L);
    account.setAccountUuid(ACCOUNT_UUID);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION);
    account.setAccountUuidSourceNumericId(17L);
    return account;
  }

  private static Record lockedAccountRecord() {
    Record lockedAccount = mock(Record.class);
    when(lockedAccount.get("id", Long.class)).thenReturn(17L);
    when(lockedAccount.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_UUID);
    when(lockedAccount.get("account_uuid_provenance", String.class))
        .thenReturn(AccountIdentityProvenance.ACCOUNT_V29_MIGRATION.name());
    when(lockedAccount.get("account_uuid_source_numeric_id", Long.class)).thenReturn(17L);
    return lockedAccount;
  }

  private static AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance
      freshProvenance() {
    return new AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
        null,
        TenantProvenanceKind.FRESH_GAME_DESIGN,
        OPERATION_ID,
        freshTenantEvidence().evidenceDigest());
  }

  private static Record freshMembershipRecord(
      long membershipId, long accountId, long membershipVersion, long authorityGeneration) {
    Record row = mock(Record.class);
    when(row.get("id", Long.class)).thenReturn(membershipId);
    when(row.get("account_id", Long.class)).thenReturn(accountId);
    when(row.get("tenant_id", Long.class)).thenReturn(null);
    when(row.get("tenant_uuid", UUID.class)).thenReturn(TENANT_UUID);
    when(row.get("tenant_provenance_kind", String.class)).thenReturn("FRESH_GAME_DESIGN");
    when(row.get("tenant_source_operation_id", UUID.class)).thenReturn(OPERATION_ID);
    when(row.get("tenant_provenance_digest", String.class))
        .thenReturn(freshTenantEvidence().evidenceDigest());
    when(row.get("gameplay_admission_allowed", Boolean.class)).thenReturn(true);
    when(row.get("lifecycle_state", String.class)).thenReturn("ACTIVE");
    when(row.get("membership_version", Long.class)).thenReturn(membershipVersion);
    when(row.get("membership_authority_generation", Long.class)).thenReturn(authorityGeneration);
    when(row.get("authority_provenance", String.class)).thenReturn("EXPLICIT_JOIN");
    return row;
  }
}
