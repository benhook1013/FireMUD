package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalRuntimeMembershipSnapshotService;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountCanonicalRuntimeMembershipSnapshotRepositoryTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @AfterEach
  void clearTransactionFlags() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void serviceOwnsOneWritableTransactionForTheExactSnapshotRead() {
    var repository = mock(AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    var transactionManager = new RecordingTransactionManager();
    var expected = missingSnapshot();
    when(repository.readCurrent(ACCOUNT_UUID, TENANT_UUID))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              transactionManager.events.add("owner-read");
              return expected;
            });
    var service =
        new AccountCanonicalRuntimeMembershipSnapshotService(repository, transactionManager);

    Snapshot actual = service.readCurrent(ACCOUNT_UUID, TENANT_UUID);

    assertThat(actual).isSameAs(expected);
    assertThat(transactionManager.events).containsExactly("begin", "owner-read", "commit");
    verify(repository).readCurrent(ACCOUNT_UUID, TENANT_UUID);
  }

  @Test
  void serviceRejectsAnAmbientTransactionBeforeReadingAnyOwnerState() {
    var repository = mock(AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    var transactionManager = new RecordingTransactionManager();
    var service =
        new AccountCanonicalRuntimeMembershipSnapshotService(repository, transactionManager);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> service.readCurrent(ACCOUNT_UUID, TENANT_UUID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without an ambient transaction");

    assertThat(transactionManager.events).isEmpty();
    verifyNoInteractions(repository);
  }

  private static Snapshot missingSnapshot() {
    AuthorityTuple tuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(TENANT_UUID.toString(), "1"),
            Map.of(TENANT_UUID.toString(), "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    return new Snapshot(
        "firemud-account-service",
        ACCOUNT_UUID.toString(),
        TENANT_UUID.toString(),
        false,
        "MISSING",
        false,
        Map.of(TENANT_UUID.toString(), "1"),
        "1",
        List.of(),
        tuple,
        "1",
        List.of(
            new Checkpoint("account:auth-authority:v1:account/" + ACCOUNT_UUID, "0"),
            new Checkpoint("account:auth-authority:v1:issuer/firemud-account-service", "0"),
            new Checkpoint(
                "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID, "0"),
            new Checkpoint("account:auth-authority:v1:tenant/" + TENANT_UUID, "0")),
        List.of());
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final java.util.ArrayList<String> events = new java.util.ArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException("Unexpected ambient Account transaction");
      }
      events.add("begin");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      events.add("commit");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Override
    public void rollback(TransactionStatus status) {
      events.add("rollback");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
  }
}
