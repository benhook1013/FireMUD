package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.firedevops.firemud.accountservice.dto.AccountGameplayPublicAdmissionSourceSnapshot;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.service.AccountGameplayPublicAdmissionSourceReader;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionInitialTokenAuthenticator;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionInitialTokenAuthenticator.CurrentInitialTokenObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAuthenticatedPublicSourceOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAuthenticatedPublicSourceOwner.PublicSourceObservationException;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner doubles prove composition only; signed-token and PostgreSQL/Redis proof are separate. */
class AccountGameplayAuthenticatedPublicSourceOwnerTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID OPERATION = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();
  private static final UUID JTI = UUID.randomUUID();
  private static final String TOKEN = "compact-secret-token";
  private static final long NOW = 1_800_000_000L;

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesBeforeOwnedWritableSerializableSnapshotAndPreservesSources() {
    Fixture f = new Fixture();

    var result = f.observe();

    assertThat(f.events).containsExactly("authenticate", "begin", "source", "commit");
    assertThat(f.transactions.definition.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
    assertThat(f.transactions.definition.isReadOnly()).isFalse();
    assertThat(f.transactions.definition.getTimeout()).isEqualTo(3);
    assertThat(result.tokenIdentity()).isEqualTo(f.identity);
    assertThat(result.tokenIssuedAtEpochSecond()).isEqualTo(NOW - 5);
    assertThat(result.tokenExpiresAtEpochSecond()).isEqualTo(NOW + 20);
    assertThat(result.capturedAtEpochSecond()).isEqualTo(NOW);
    assertThat(result.sourceSnapshot()).isSameAs(f.snapshot);
    assertThat(result.toString()).contains("non-authorizing", "redacted").doesNotContain(TOKEN);
    verify(f.sources).readCurrent(ACCOUNT, TENANT, f.identity);
    // No comparison with the initial token's empty membershipVersion: current evidence is kept.
  }

  @Test
  void ambientTransactionRejectsBeforeAuthentication() {
    Fixture f = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertDenied(f);

    verifyNoInteractions(f.authenticator, f.sources);
    assertThat(f.events).isEmpty();
  }

  @Test
  void authenticationFailureNeverStartsSourceTransaction() {
    Fixture f = new Fixture();
    doThrow(new IllegalStateException(TOKEN)).when(f.authenticator).authenticateInitialToken(TOKEN);

    assertDenied(f);

    assertThat(f.events).isEmpty();
    verifyNoInteractions(f.sources);
  }

  @Test
  void rejectsWrongSubjectsTenantLifecycleGlobalGenerationFenceCutoffAndSqlIdentity() {
    List<Consumer<Fixture>> changes =
        List.of(
            f -> when(f.snapshot.membershipSource().membership().accountId()).thenReturn(TENANT),
            f -> when(f.snapshot.membershipSource().membership().tenantId()).thenReturn(ACCOUNT),
            f -> when(f.snapshot.accountLifecycleState()).thenReturn(AccountLifecycleState.DELETED),
            f ->
                when(f.snapshot.membershipSource().currentAuthority().issuer().generation())
                    .thenReturn(2L),
            f ->
                when(f.snapshot.membershipSource().currentAuthority().account().generation())
                    .thenReturn(2L),
            f ->
                when(f.snapshot.membershipSource().issuerAccountSources().issuer().generation())
                    .thenReturn(2L),
            f ->
                when(f.snapshot.membershipSource().issuerAccountSources().account().generation())
                    .thenReturn(2L),
            f ->
                when(f.snapshot.membershipSource().issuerAccountSources().account().scope())
                    .thenReturn(AuthorityScope.account(TENANT)),
            f ->
                when(f.snapshot.membershipSource().currentAuthority().issuanceFence())
                    .thenReturn(new IssuanceFence(ACCOUNT, 2, 2)),
            f ->
                when(f.snapshot
                        .membershipSource()
                        .issuerAccountSources()
                        .account()
                        .accountSecurityCutoff())
                    .thenReturn(null),
            f ->
                when(f.snapshot.tokenIdentityFence())
                    .thenReturn(
                        new AccountGameplayTokenIdentityFence(
                            new TokenIdentity(
                                ACCOUNT, OPERATION, REQUEST, "b".repeat(64), JTI, NOW - 5, 1, 1),
                            1,
                            State.ACTIVE,
                            null,
                            null)),
            f ->
                when(f.snapshot.tokenIdentityFence())
                    .thenReturn(
                        new AccountGameplayTokenIdentityFence(
                            f.identity, 2, State.PENDING, UUID.randomUUID(), "c".repeat(64))));
    for (Consumer<Fixture> change : changes) {
      Fixture f = new Fixture();
      change.accept(f);

      assertDenied(f);

      assertThat(f.events).containsExactly("authenticate", "begin", "source", "rollback");
    }
  }

  @Test
  void changedApplicableAccountCutoffRejects() {
    Fixture f = new Fixture();
    AccountAuthoritySnapshot signed = mock(AccountAuthoritySnapshot.class);
    when(signed.accountId()).thenReturn(ACCOUNT);
    when(signed.issuerGeneration()).thenReturn(1L);
    when(signed.accountGeneration()).thenReturn(1L);
    when(signed.issuanceFence()).thenReturn(1L);
    when(signed.accountSecurityCutoff())
        .thenReturn(
            Optional.of(mock(GameSessionAccountDelegationProfile.AccountSecurityCutoff.class)));
    when(f.candidate.authoritySnapshot()).thenReturn(signed);

    assertDenied(f);
  }

  @Test
  void expiryBeforeSourceReadAndDuringReadRejects() {
    Fixture before = new Fixture();
    when(before.clock.instant()).thenReturn(Instant.ofEpochSecond(NOW + 20));
    assertDenied(before);
    verifyNoInteractions(before.sources);

    Fixture during = new Fixture();
    when(during.clock.instant())
        .thenReturn(
            Instant.ofEpochSecond(NOW),
            Instant.ofEpochSecond(NOW),
            Instant.ofEpochSecond(NOW + 20));
    assertDenied(during);
    assertThat(during.events).containsExactly("authenticate", "begin", "source", "rollback");
  }

  @Test
  void expiryDuringTransactionCompletionRejectsReturnedObservation() {
    Fixture f = new Fixture();
    when(f.clock.instant())
        .thenReturn(
            Instant.ofEpochSecond(NOW),
            Instant.ofEpochSecond(NOW),
            Instant.ofEpochSecond(NOW),
            Instant.ofEpochSecond(NOW + 20));

    assertDenied(f);

    assertThat(f.events).containsExactly("authenticate", "begin", "source", "commit");
  }

  @Test
  void unavailableSourcesAndIncorrectTransactionContextFailClosed() {
    Fixture unavailable = new Fixture();
    doThrow(new IllegalStateException(TOKEN))
        .when(unavailable.sources)
        .readCurrent(ACCOUNT, TENANT, unavailable.identity);
    assertDenied(unavailable);

    Fixture missing = new Fixture();
    doReturn(null).when(missing.sources).readCurrent(ACCOUNT, TENANT, missing.identity);
    assertDenied(missing);

    Fixture wrongIsolation = new Fixture();
    wrongIsolation.transactions.establishedIsolation =
        TransactionDefinition.ISOLATION_READ_COMMITTED;
    assertDenied(wrongIsolation);
    verifyNoInteractions(wrongIsolation.sources);
  }

  private static void assertDenied(Fixture f) {
    assertThatThrownBy(f::observe)
        .isInstanceOf(PublicSourceObservationException.class)
        .hasMessage("Current Account public source observation failed")
        .hasNoCause();
  }

  private static final class Fixture {
    private final List<String> events = new ArrayList<>();
    private final AccountGameplayAdmissionInitialTokenAuthenticator authenticator =
        mock(AccountGameplayAdmissionInitialTokenAuthenticator.class);
    private final AccountGameplayPublicAdmissionSourceReader sources =
        mock(AccountGameplayPublicAdmissionSourceReader.class);
    private final CommittedCandidateVerificationData candidate =
        mock(CommittedCandidateVerificationData.class);
    private final AccountGameplayPublicAdmissionSourceSnapshot snapshot =
        mock(AccountGameplayPublicAdmissionSourceSnapshot.class, RETURNS_DEEP_STUBS);
    private final Clock clock = mock(Clock.class);
    private final RecordingTransactions transactions = new RecordingTransactions(events);
    private final TokenIdentity identity =
        new TokenIdentity(ACCOUNT, OPERATION, REQUEST, "a".repeat(64), JTI, NOW - 5, 1, 1);
    private final AccountGameplayAuthenticatedPublicSourceOwner owner;

    private Fixture() {
      when(clock.instant()).thenReturn(Instant.ofEpochSecond(NOW));
      var pending = mock(AccountGameplayDelegationPendingIdentity.class);
      when(pending.accountId()).thenReturn(ACCOUNT);
      when(pending.operationId()).thenReturn(OPERATION);
      when(pending.requestId()).thenReturn(REQUEST);
      when(pending.tokenJti()).thenReturn(JTI);
      when(pending.issuedAtEpochSecond()).thenReturn(NOW - 5);
      when(pending.notBeforeEpochSecond()).thenReturn(NOW - 5);
      when(pending.expiresAtEpochSecond()).thenReturn(NOW + 20);
      when(candidate.identity()).thenReturn(pending);
      when(candidate.tokenSha256()).thenReturn("a".repeat(64));
      when(candidate.authoritySnapshot())
          .thenReturn(new AccountAuthoritySnapshot(ACCOUNT, 1, 1, 1, 1, 1, 1, Optional.empty()));
      var observation = mock(CurrentInitialTokenObservation.class);
      when(observation.candidate()).thenReturn(candidate);
      when(authenticator.authenticateInitialToken(TOKEN))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                events.add("authenticate");
                return observation;
              });
      when(snapshot.accountLifecycleState()).thenReturn(AccountLifecycleState.ACTIVE);
      when(snapshot.membershipSource().membership().accountId()).thenReturn(ACCOUNT);
      when(snapshot.membershipSource().membership().tenantId()).thenReturn(TENANT);
      var issuerScope = AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER);
      var accountScope = AuthorityScope.account(ACCOUNT);
      var current = snapshot.membershipSource().currentAuthority();
      when(current.issuer().scope()).thenReturn(issuerScope);
      when(current.account().scope()).thenReturn(accountScope);
      when(current.issuer().generation()).thenReturn(1L);
      when(current.account().generation()).thenReturn(1L);
      var fence = new IssuanceFence(ACCOUNT, 1, 1);
      when(current.issuanceFence()).thenReturn(fence);
      var source = snapshot.membershipSource().issuerAccountSources();
      when(source.issuer().scope()).thenReturn(issuerScope);
      when(source.account().scope()).thenReturn(accountScope);
      when(source.issuer().generation()).thenReturn(1L);
      when(source.account().generation()).thenReturn(1L);
      when(source.account().accountSecurityCutoff()).thenReturn(Optional.empty());
      when(source.issuanceFence()).thenReturn(fence);
      when(source.account().issuanceFence()).thenReturn(fence);
      when(snapshot.tokenIdentityFence())
          .thenReturn(new AccountGameplayTokenIdentityFence(identity, 1, State.ACTIVE, null, null));
      when(sources.readCurrent(ACCOUNT, TENANT, identity))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                events.add("source");
                return snapshot;
              });
      owner =
          new AccountGameplayAuthenticatedPublicSourceOwner(
              authenticator, sources, transactions, clock, 3);
    }

    private AccountGameplayAuthenticatedPublicSourceOwner.CurrentPublicSourceObservation observe() {
      return owner.observeCurrent(TOKEN, TENANT);
    }
  }

  private static final class RecordingTransactions implements PlatformTransactionManager {
    private final List<String> events;
    private TransactionDefinition definition;
    private int establishedIsolation = TransactionDefinition.ISOLATION_SERIALIZABLE;

    private RecordingTransactions(List<String> events) {
      this.events = events;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      this.definition = definition == null ? new DefaultTransactionDefinition() : definition;
      events.add("begin");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(this.definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(establishedIsolation);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      events.add("commit");
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      events.add("rollback");
      TransactionSynchronizationManager.clear();
    }
  }
}
