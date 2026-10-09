package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AccountCreatorMembershipSourceReaderTest {
  @Mock private AccountRepository accounts;
  @Mock private AccountJoinOperationRepository joins;
  @Mock private AccountTenantMembershipRepository memberships;
  @Mock private AccountMembershipPairAuthorityRepository pairs;
  @Mock private AccountTenantMembershipRoleSnapshotRepository roles;
  @Mock private FreshTenantIdentityAssociationRepository fresh;
  @Mock private AccountAuthorityGenerationRepository generations;
  @Mock private AccountAuthorityOutboxRepository outbox;
  @Mock private AccountAuthoritySourceEvidenceRepository sources;
  @Mock private AccountTenantCreationBootstrapOperationRepository operations;
  @Mock private CompositeSnapshot upstream;
  @InjectMocks private AccountCreatorMembershipSourceReader reader;

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void requiresWritableOwnerTransactionBeforeReadingOrEnrollingAnything() {
    assertThatThrownBy(
            () ->
                reader.readFreshNeverJoinedMembershipSnapshot(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(
        accounts,
        joins,
        memberships,
        pairs,
        roles,
        fresh,
        generations,
        outbox,
        sources,
        operations);
  }

  @Test
  void missingGenuineUpstreamSourcesCannotCreateAnAbsenceBaseline() {
    UUID accountUuid = UUID.randomUUID();
    UUID tenantUuid = UUID.randomUUID();
    Account account = new Account();
    account.setId(17L);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidSourceNumericId(17L);
    account.setAccountUuidProvenance(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    when(accounts.findByAccountUuidForUpdate(accountUuid)).thenReturn(Optional.of(account));
    when(fresh.read(tenantUuid)).thenReturn(Optional.of(creation(tenantUuid)));
    when(generations.readCompositeSnapshot(
            "firemud-account-service", accountUuid, List.of(tenantUuid), List.of()))
        .thenReturn(upstream);
    when(sources.readCurrentIssuerAccountSources("firemud-account-service", accountUuid))
        .thenThrow(new IllegalStateException("Genuine Account source baseline is unavailable"));
    assertThatThrownBy(() -> reader.readFreshNeverJoinedMembershipSnapshot(accountUuid, tenantUuid))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source baseline is unavailable");
    verifyNoInteractions(memberships, pairs, roles, outbox, operations);
  }

  private static FreshTenantCreationEvidence creation(UUID tenantUuid) {
    UUID request = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    String namespace = "creator-source-proof";
    String key = "creator-game";
    String digest =
        GameTenantCreationDigest.requestDigest(namespace, request, key, "Creator", null);
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        request,
        operation,
        digest,
        tenantUuid,
        17L,
        key,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            namespace, request, operation, digest, tenantUuid, 17L, key, "NEW_GAME_ROW"));
  }
}
