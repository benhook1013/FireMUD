package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountIssuerGenerationProjectionPublisherTest {
  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  private final AccountAuthoritySourceEvidenceRepository sources =
      mock(AccountAuthoritySourceEvidenceRepository.class);
  private final AccountGameplayDelegationRedisClient redis =
      mock(AccountGameplayDelegationRedisClient.class);

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void unknownIssuerDeniesBeforeAnyCollaborator() {
    var manager = mock(PlatformTransactionManager.class);
    var publisher = new AccountIssuerGenerationProjectionPublisher(sources, manager, redis);
    assertThatThrownBy(() -> publisher.refreshCurrent("other-issuer"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(manager, sources, redis);
  }

  @Test
  void ambientTransactionOrSynchronizationDeniesBeforeAnyCollaborator() {
    var manager = mock(PlatformTransactionManager.class);
    var publisher = new AccountIssuerGenerationProjectionPublisher(sources, manager, redis);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> publisher.refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inherit");
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> publisher.refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("inherit");
    verifyNoInteractions(manager, sources, redis);
  }

  @Test
  void readsTwiceAroundRedisAfterEachActualTemplateTransactionCompletes() {
    var manager = new TrackingManager();
    var snapshot = baseline(5);
    var projection = IssuerGenerationProjection.fromSource(snapshot);
    when(sources.readCurrentCanonicalIssuerSource(ISSUER))
        .thenAnswer(
            ignored -> {
              assertThat(manager.active).isTrue();
              return snapshot;
            });
    when(redis.publishIssuerAuthorityProjection(projection))
        .thenAnswer(
            ignored -> {
              assertThat(manager.active).isFalse();
              assertThat(manager.commits).isEqualTo(1);
              return AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY;
            });
    var actual =
        new AccountIssuerGenerationProjectionPublisher(sources, manager, redis)
            .refreshCurrent(ISSUER);
    assertThat(actual.key()).isEqualTo(projection.key());
    assertThat(actual.json()).isEqualTo(projection.toJson());
    assertThat(actual.outcome())
        .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
    assertThat(manager.commits).isEqualTo(2);
    assertThat(manager.active).isFalse();
    var order = inOrder(sources, redis);
    order.verify(sources).readCurrentCanonicalIssuerSource(ISSUER);
    order.verify(redis).publishIssuerAuthorityProjection(projection);
    order.verify(sources).readCurrentCanonicalIssuerSource(ISSUER);
    order.verifyNoMoreInteractions();
  }

  @Test
  void changedFullSourceRefusesEvenWhenProjectedBytesAreIdenticalWithoutImplicitRetry() {
    var before = baseline(5);
    var after = baseline(6);
    assertThat(IssuerGenerationProjection.fromSource(before))
        .isEqualTo(IssuerGenerationProjection.fromSource(after));
    when(sources.readCurrentCanonicalIssuerSource(ISSUER)).thenReturn(before, after);
    when(redis.publishIssuerAuthorityProjection(any()))
        .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
    assertThatThrownBy(
            () ->
                new AccountIssuerGenerationProjectionPublisher(
                        sources, new TrackingManager(), redis)
                    .refreshCurrent(ISSUER))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicit retry");
    verify(sources, times(2)).readCurrentCanonicalIssuerSource(ISSUER);
    verify(redis).publishIssuerAuthorityProjection(IssuerGenerationProjection.fromSource(before));
    verifyNoMoreInteractions(sources, redis);
  }

  @Test
  void redisFailureDoesNotReadSqlAgainOrRetry() {
    var snapshot = baseline(5);
    var failure = new IllegalStateException("controlled Redis failure");
    when(sources.readCurrentCanonicalIssuerSource(ISSUER)).thenReturn(snapshot);
    when(redis.publishIssuerAuthorityProjection(any())).thenThrow(failure);
    assertThatThrownBy(
            () ->
                new AccountIssuerGenerationProjectionPublisher(
                        sources, new TrackingManager(), redis)
                    .refreshCurrent(ISSUER))
        .isSameAs(failure);
    verify(sources).readCurrentCanonicalIssuerSource(ISSUER);
    verify(redis).publishIssuerAuthorityProjection(IssuerGenerationProjection.fromSource(snapshot));
    verifyNoMoreInteractions(sources, redis);
  }

  private static CanonicalIssuerSourceSnapshot baseline(long initializationTransaction) {
    return new CanonicalIssuerSourceSnapshot(
        new CurrentSourceEvidence(
            AuthorityScope.issuer(ISSUER),
            1,
            1,
            null,
            new SourceCheckpoint(
                "account:auth-authority:v1:issuer/" + ISSUER,
                0,
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            initializationTransaction,
            null),
        Optional.empty());
  }

  private static final class TrackingManager implements PlatformTransactionManager {
    private boolean active;
    private int commits;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(active).isFalse();
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.isReadOnly()).isFalse();
      active = true;
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      assertThat(active).isTrue();
      active = false;
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
      active = false;
    }
  }
}
