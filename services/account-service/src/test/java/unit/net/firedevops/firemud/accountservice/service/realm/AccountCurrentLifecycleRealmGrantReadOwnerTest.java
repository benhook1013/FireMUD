package unit.net.firedevops.firemud.accountservice.service.realm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.CurrentGrant;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.Receipt;
import net.firedevops.firemud.accountservice.repository.AccountLifecycleBoundRealmGrantRepository.State;
import net.firedevops.firemud.accountservice.service.realm.AccountCurrentLifecycleRealmGrantReadOwner;
import net.firedevops.firemud.accountservice.service.realm.AccountCurrentLifecycleRealmGrantReadOwner.Status;
import net.firedevops.firemud.accountservice.testsupport.CurrentPrivatePlaytestReadTransportFixture;
import net.firedevops.firemud.common.account.authority.GrantAuthorityScopeCodec.Scope;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled GS mTLS source and repository outcomes; physical SQL is proved separately. */
class AccountCurrentLifecycleRealmGrantReadOwnerTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;

  @BeforeAll
  static void createPki() throws Exception {
    pki = CurrentPrivatePlaytestReadTransportFixture.createPki(directory);
  }

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void obtainsActualSourceOutsideSqlThenUsesBoundedReadOnlyAccountTransaction() throws Exception {
    Scope expected = scope();
    var repository = mock(AccountLifecycleBoundRealmGrantRepository.class);
    var transactions = new LocalTransactions();
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      Instant expiry = Instant.now().plusSeconds(300);
      transport.current(
          Optional.of(
              CurrentPrivatePlaytestReadTransportFixture.snapshot(
                  sourceScope(expected), expected.playtestLifecycleId(), expiry, Instant.now())));
      var receipt = mock(Receipt.class);
      var grant = mock(CurrentGrant.class);
      when(receipt.scope()).thenReturn(expected);
      when(receipt.state()).thenReturn(State.ACTIVE);
      when(grant.receipt()).thenReturn(receipt);
      when(grant.active()).thenReturn(true);
      when(repository.readCurrent(expected, expiry))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                    .isTrue();
                assertThat(transport.calls()).isEqualTo(1);
                return Optional.of(grant);
              });
      var result =
          new AccountCurrentLifecycleRealmGrantReadOwner(
                  transport.client(),
                  repository,
                  transactions,
                  CurrentPrivatePlaytestReadTransportFixture.NAMESPACE)
              .read(expected);
      assertThat(result.status()).isEqualTo(Status.ACTIVE_AS_OF_READ);
      assertThat(result.grant()).contains(grant);
      assertThat(result.admissionAllowed()).isFalse();
      assertThat(transactions.begins).isEqualTo(1);
      assertThat(transactions.commits).isEqualTo(1);
      // Controlled component observations: an expired GS source cannot regain current status
      // when the separate Account component still reports active under its own clock.
      Instant expiredFork = Instant.now().minusSeconds(1);
      when(repository.readCurrent(expected, expiredFork)).thenReturn(Optional.of(grant));
      transport.current(
          Optional.of(
              CurrentPrivatePlaytestReadTransportFixture.snapshot(
                  sourceScope(expected),
                  expected.playtestLifecycleId(),
                  expiredFork,
                  Instant.now())));
      var expired =
          new AccountCurrentLifecycleRealmGrantReadOwner(
                  transport.client(),
                  repository,
                  transactions,
                  CurrentPrivatePlaytestReadTransportFixture.NAMESPACE)
              .read(expected);
      assertThat(expired.status()).isEqualTo(Status.EXPIRED);
      assertThat(expired.grant()).contains(grant);
      assertThat(expired.admissionAllowed()).isFalse();
    }
  }

  @Test
  void absentSourceAndWrongCurrentLifecycleNeverReadAccountGrant() throws Exception {
    Scope expected = scope();
    var repository = mock(AccountLifecycleBoundRealmGrantRepository.class);
    var transactions = new LocalTransactions();
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      var owner =
          new AccountCurrentLifecycleRealmGrantReadOwner(
              transport.client(),
              repository,
              transactions,
              CurrentPrivatePlaytestReadTransportFixture.NAMESPACE);
      assertThat(owner.read(expected).status()).isEqualTo(Status.SOURCE_ABSENT);
      transport.current(
          Optional.of(
              CurrentPrivatePlaytestReadTransportFixture.snapshot(
                  sourceScope(expected),
                  UUID.randomUUID(),
                  Instant.now().plusSeconds(300),
                  Instant.now())));
      assertThat(owner.read(expected).status()).isEqualTo(Status.CURRENT_LIFECYCLE_MISMATCH);
      verifyNoInteractions(repository);
      assertThat(transactions.begins).isZero();
    }
  }

  @Test
  void absentAccountGrantRemainsTruthfulAndAmbientSynchronizationRefusesBeforeSource()
      throws Exception {
    Scope expected = scope();
    var repository = mock(AccountLifecycleBoundRealmGrantRepository.class);
    when(repository.readCurrent(any(), any())).thenReturn(Optional.empty());
    try (var transport = new CurrentPrivatePlaytestReadTransportFixture(pki, directory, "gs")) {
      transport.current(
          Optional.of(
              CurrentPrivatePlaytestReadTransportFixture.snapshot(
                  sourceScope(expected),
                  expected.playtestLifecycleId(),
                  Instant.now().plusSeconds(300),
                  Instant.now())));
      var owner =
          new AccountCurrentLifecycleRealmGrantReadOwner(
              transport.client(),
              repository,
              new LocalTransactions(),
              CurrentPrivatePlaytestReadTransportFixture.NAMESPACE);
      assertThat(owner.read(expected).status()).isEqualTo(Status.GRANT_ABSENT);
      int before = transport.calls();
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> owner.read(expected)).isInstanceOf(IllegalStateException.class);
      assertThat(transport.calls()).isEqualTo(before);
    }
  }

  private static Scope scope() {
    return new Scope(UUID.randomUUID(), UUID.randomUUID(), "world", "realm", UUID.randomUUID());
  }

  private static CurrentPrivatePlaytestLifecycleReadEvidence.Scope sourceScope(Scope scope) {
    return new CurrentPrivatePlaytestLifecycleReadEvidence.Scope(
        CurrentPrivatePlaytestReadTransportFixture.NAMESPACE,
        scope.tenantId(),
        scope.worldSlug(),
        scope.realmSlug());
  }

  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    private static final long serialVersionUID = 1L;
    int begins;
    int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isTrue();
      assertThat(definition.getTimeout()).isEqualTo(5);
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
