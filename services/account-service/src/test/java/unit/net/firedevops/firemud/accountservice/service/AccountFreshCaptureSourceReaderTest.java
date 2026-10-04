package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountFreshCaptureSources;
import net.firedevops.firemud.accountservice.dto.AccountMembershipCaptureSources;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGlobalRoleSourceRepository.FreshEmptySource;
import net.firedevops.firemud.accountservice.service.AccountFreshCaptureSourceReader;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountFreshCaptureSourceReaderTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("a50fdfca-f085-4a65-9ea1-ec99cda0f645");
  private static final UUID TENANT_UUID = UUID.fromString("8e35f45a-fd75-496e-aa40-a2a4da03ee4c");

  private final AccountMembershipAuthorityEventProducer membershipProducer =
      mock(AccountMembershipAuthorityEventProducer.class);
  private final AccountGlobalRoleSourceRepository globalRoleSourceRepository =
      mock(AccountGlobalRoleSourceRepository.class);
  private final AccountFreshCaptureSourceReader reader =
      new AccountFreshCaptureSourceReader(membershipProducer, globalRoleSourceRepository);

  @BeforeEach
  void markCallerOwnedWritableTransactionActive() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsMembershipVectorBeforeFreshRoleSourceInTheCallerTransaction() {
    AccountMembershipCaptureSources membershipSources = mock(AccountMembershipCaptureSources.class);
    when(membershipSources.requestedAccountUuid()).thenReturn(ACCOUNT_UUID);
    when(membershipSources.requestedTenantUuid()).thenReturn(TENANT_UUID);
    FreshEmptySource roleSource = freshSource(ACCOUNT_UUID);
    when(membershipProducer.readExistingRuntimeMembershipCaptureSources(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(membershipSources);
    when(globalRoleSourceRepository.readFreshEmptySourceForUpdate(ACCOUNT_UUID))
        .thenReturn(roleSource);

    AccountFreshCaptureSources result = reader.readExisting(ACCOUNT_UUID, TENANT_UUID);

    assertThat(result.membershipSources()).isSameAs(membershipSources);
    assertThat(result.freshGlobalRoleSource()).isSameAs(roleSource);
    InOrder sourceOrder = inOrder(membershipProducer, globalRoleSourceRepository);
    sourceOrder
        .verify(membershipProducer)
        .readExistingRuntimeMembershipCaptureSources(ACCOUNT_UUID, TENANT_UUID);
    sourceOrder.verify(globalRoleSourceRepository).readFreshEmptySourceForUpdate(ACCOUNT_UUID);
  }

  @Test
  void rejectsAbsentRoleSourceWithoutReturningPartialMembershipSources() {
    AccountMembershipCaptureSources membershipSources = mock(AccountMembershipCaptureSources.class);
    when(membershipProducer.readExistingRuntimeMembershipCaptureSources(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(membershipSources);
    doThrow(new IllegalStateException("Fresh Account global-role source is missing"))
        .when(globalRoleSourceRepository)
        .readFreshEmptySourceForUpdate(ACCOUNT_UUID);

    assertThatThrownBy(() -> reader.readExisting(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("global-role source is missing");
    inOrder(membershipProducer, globalRoleSourceRepository)
        .verify(globalRoleSourceRepository)
        .readFreshEmptySourceForUpdate(ACCOUNT_UUID);
  }

  @Test
  void rejectsCallsOutsideWritableOwnerTransactionsBeforeReadingEitherSource() {
    TransactionSynchronizationManager.setActualTransactionActive(false);

    assertThatThrownBy(() -> reader.readExisting(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable caller-owned");
    verifyNoInteractions(membershipProducer, globalRoleSourceRepository);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> reader.readExisting(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable caller-owned");
    verifyNoInteractions(membershipProducer, globalRoleSourceRepository);
  }

  private static FreshEmptySource freshSource(UUID accountUuid) {
    return new FreshEmptySource(
        accountUuid, 73L, 73L, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, List.of(), 1L);
  }
}
