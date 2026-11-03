package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ExpectedBinding;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ObservedSubscription;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ResolutionPreparation;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.TransitionResult;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.Authorization;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.AuthorizedActor;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.LockedTarget;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.Request;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AccountAuthenticatedActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountHostingSubscriptionTerminalResolutionServiceTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID ISSUANCE = UUID.randomUUID();
  private static final String TOKEN_HASH = "a".repeat(64);
  private static final UUID RESOLUTION = UUID.randomUUID();
  private static final UUID FAILED_REQUEST = UUID.randomUUID();
  private static final UUID CREATION = UUID.randomUUID();
  private static final UUID SUBSCRIPTION = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID AUDIT = UUID.randomUUID();
  private static final String CONTROL_UI_JWT = "controlled-test-jwt";
  private static final String EMAIL = "account@example.test";
  private static final String SECRET = "test-primary-secret";
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

  @AfterEach
  void clearAmbientTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void providerReadSeparatesCurrentActorCaptureFromFinalPrimaryAuthenticationAndMutation() {
    Fixture fixture = new Fixture();
    AccountAuthenticatedActor actor = actor(ACCOUNT, ISSUANCE, TOKEN_HASH);
    fixture.currentActors(actor, actor);
    fixture.prepare(null);
    fixture.observeOutsideOwnerTransaction();
    fixture.applyAfterAuthorization(ACCOUNT);
    fixture.primaryAuthenticatesAs(ACCOUNT);

    TransitionResult result = fixture.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input());

    assertThat(result).isSameAs(fixture.committed);
    assertThat(fixture.events)
        .containsSubsequence(
            "prepare",
            "provider-read",
            "current-actor-2",
            "apply",
            "primary-authentication",
            "resolution-write");
    assertThat(fixture.commits.commits).isEqualTo(2);
    assertThat(fixture.resolutionWrites.get()).isEqualTo(1);
    Request request = fixture.preparedRequest.get();
    assertThat(request.actorAccountId()).isEqualTo(ACCOUNT);
    assertThat(request.controlUiIssuanceOperationId()).isEqualTo(ISSUANCE);
    assertThat(request.controlUiTokenHash()).isEqualTo(TOKEN_HASH);
    AuthorizedActor authorized = fixture.authorizedActor.get();
    assertThat(authorized.actorAccountId()).isEqualTo(ACCOUNT);
    assertThat(authorized.controlUiIssuanceOperationId()).isEqualTo(ISSUANCE);
    assertThat(authorized.controlUiTokenHash()).isEqualTo(TOKEN_HASH);
    assertThat(authorized.authenticatedAt()).isEqualTo(NOW);
  }

  @Test
  void changedCurrentAccountIssuanceOrTokenSourceCannotReachResolutionAuthorization() {
    List<AccountAuthenticatedActor> mismatches =
        List.of(
            actor(UUID.randomUUID(), ISSUANCE, TOKEN_HASH),
            actor(ACCOUNT, UUID.randomUUID(), TOKEN_HASH),
            actor(ACCOUNT, ISSUANCE, "b".repeat(64)));

    for (AccountAuthenticatedActor changedActor : mismatches) {
      Fixture fixture = new Fixture();
      fixture.currentActors(actor(ACCOUNT, ISSUANCE, TOKEN_HASH), changedActor);
      fixture.prepare(null);
      fixture.observeOutsideOwnerTransaction();
      fixture.applyAfterAuthorization(ACCOUNT);

      Throwable failure =
          catchThrowable(() -> fixture.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()));

      assertThat(failure).isInstanceOf(FenceRejectedException.class);
      assertThat(fixture.events).contains("provider-read", "current-actor-2");
      assertThat(fixture.resolutionWrites).hasValue(0);
      verify(fixture.repository, never())
          .applyTerminalResolution(
              any(ResolutionPreparation.class), any(), any(Authorization.class));
      verifyNoInteractions(fixture.primaryAuthentication);
    }
  }

  @Test
  void unavailableCurrentActorStopsBeforeProviderOrFinalAuthorization() {
    Fixture beforeProvider = new Fixture();
    beforeProvider.currentActorUnavailableOnFirstCall();
    assertThatThrownBy(() -> beforeProvider.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(
        beforeProvider.repository, beforeProvider.observer, beforeProvider.primaryAuthentication);

    Fixture afterProvider = new Fixture();
    afterProvider.currentActorAvailableThenUnavailable(actor(ACCOUNT, ISSUANCE, TOKEN_HASH));
    afterProvider.prepare(null);
    afterProvider.observeOutsideOwnerTransaction();
    afterProvider.applyAfterAuthorization(ACCOUNT);
    assertThatThrownBy(() -> afterProvider.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(afterProvider.events)
        .containsSubsequence("provider-read", "final-actor-unavailable");
    assertThat(afterProvider.resolutionWrites).hasValue(0);
    verifyNoInteractions(afterProvider.primaryAuthentication);
    verify(afterProvider.repository, never())
        .applyTerminalResolution(any(ResolutionPreparation.class), any(), any(Authorization.class));
  }

  @Test
  void wrongLockedBillingOwnerOrPrimaryAccountCannotWriteResolution() {
    Fixture wrongOwner = new Fixture();
    AccountAuthenticatedActor actor = actor(ACCOUNT, ISSUANCE, TOKEN_HASH);
    wrongOwner.currentActors(actor, actor);
    wrongOwner.prepare(null);
    wrongOwner.observeOutsideOwnerTransaction();
    wrongOwner.applyAfterAuthorization(UUID.randomUUID());
    wrongOwner.primaryAuthenticatesAs(ACCOUNT);

    assertThatThrownBy(() -> wrongOwner.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()))
        .isInstanceOf(FenceRejectedException.class)
        .hasMessageContaining("billing-owner");
    assertThat(wrongOwner.resolutionWrites).hasValue(0);
    verifyNoInteractions(wrongOwner.primaryAuthentication);

    Fixture wrongPrimaryAccount = new Fixture();
    wrongPrimaryAccount.currentActors(actor, actor);
    wrongPrimaryAccount.prepare(null);
    wrongPrimaryAccount.observeOutsideOwnerTransaction();
    wrongPrimaryAccount.applyAfterAuthorization(ACCOUNT);
    wrongPrimaryAccount.primaryAuthenticatesAs(UUID.randomUUID());

    assertThatThrownBy(
            () -> wrongPrimaryAccount.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()))
        .isInstanceOf(FenceRejectedException.class)
        .hasMessageContaining("another Account");
    assertThat(wrongPrimaryAccount.resolutionWrites).hasValue(0);
    assertThat(wrongPrimaryAccount.events).contains("primary-authentication");
  }

  @Test
  void deniedPrimaryAuthenticationCommitsFailureAccountingBeforeGenericErrorReturns() {
    Fixture fixture = new Fixture();
    AccountAuthenticatedActor actor = actor(ACCOUNT, ISSUANCE, TOKEN_HASH);
    fixture.currentActors(actor, actor);
    fixture.prepare(null);
    fixture.observeOutsideOwnerTransaction();
    fixture.applyAfterAuthorization(ACCOUNT);
    fixture.primaryAuthenticationDenied();

    Throwable failure =
        catchThrowable(() -> fixture.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()));

    assertThat(failure).isInstanceOf(AuthenticationException.class);
    AuthenticationException error = (AuthenticationException) failure;
    assertThat(error.getCode()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(error).hasMessage("Account reauthentication failed");
    assertThat(error.getCause()).isNull();
    assertThat(error.toString()).doesNotContain(EMAIL, SECRET);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(fixture.failedAttemptCommitted).isTrue();
    assertThat(fixture.commits.commits).isEqualTo(2);
    assertThat(fixture.resolutionWrites).hasValue(0);
    assertThat(fixture.events)
        .containsSubsequence(
            "primary-authentication", "current-account-return-2", "failed-login-attempt-committed");
  }

  @Test
  void exactAuthenticatedReplayReturnsWithoutAnotherProviderReadOrPrimaryAuthentication() {
    Fixture fixture = new Fixture();
    fixture.currentActors(actor(ACCOUNT, ISSUANCE, TOKEN_HASH));
    TransitionResult replay = new TransitionResult(RESOLUTION, Outcome.COMMITTED, 12L, null);
    fixture.prepare(replay);

    TransitionResult result = fixture.service.resolve(CONTROL_UI_JWT, null, null, input());

    assertThat(result).isSameAs(replay);
    Request request = fixture.preparedRequest.get();
    assertThat(request.actorAccountId()).isEqualTo(ACCOUNT);
    assertThat(request.controlUiIssuanceOperationId()).isEqualTo(ISSUANCE);
    assertThat(request.controlUiTokenHash()).isEqualTo(TOKEN_HASH);
    assertThat(fixture.commits.commits).isEqualTo(1);
    verifyNoInteractions(fixture.observer, fixture.primaryAuthentication);
    verify(fixture.repository, never())
        .applyTerminalResolution(any(ResolutionPreparation.class), any(), any(Authorization.class));
  }

  @Test
  void ambientTransactionOrSynchronizationRefusesBeforeAnyCollaborator() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> fixture.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    TransactionSynchronizationManager.setActualTransactionActive(false);

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> fixture.service.resolve(CONTROL_UI_JWT, EMAIL, SECRET, input()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");
    verifyNoInteractions(
        fixture.actors, fixture.repository, fixture.observer, fixture.primaryAuthentication);
  }

  private static AccountHostingSubscriptionTerminalResolutionService.Input input() {
    return new AccountHostingSubscriptionTerminalResolutionService.Input(
        RESOLUTION,
        FAILED_REQUEST,
        CREATION,
        SUBSCRIPTION,
        TENANT,
        7L,
        11L,
        AUDIT,
        "operator-confirmed provider cancellation");
  }

  private static AccountAuthenticatedActor actor(
      UUID accountId, UUID issuanceId, String tokenHash) {
    AccountAuthenticatedActor actor = mock(AccountAuthenticatedActor.class);
    when(actor.accountId()).thenReturn(accountId);
    when(actor.issuanceOperationId()).thenReturn(issuanceId);
    when(actor.tokenHash()).thenReturn(tokenHash);
    return actor;
  }

  private static AccountServiceImpl.ControlUiPrimaryIdentity primaryIdentity(UUID accountId) {
    AccountServiceImpl.ControlUiPrimaryIdentity identity =
        mock(AccountServiceImpl.ControlUiPrimaryIdentity.class);
    when(identity.accountId()).thenReturn(accountId);
    return identity;
  }

  private static final class Fixture {
    private final AccountControlUiActorService actors = mock(AccountControlUiActorService.class);
    private final AccountServiceImpl primaryAuthentication = mock(AccountServiceImpl.class);
    private final AccountHostingSubscriptionLifecycleRepository repository =
        mock(AccountHostingSubscriptionLifecycleRepository.class);
    private final StripeHostingSubscriptionObserver observer =
        mock(StripeHostingSubscriptionObserver.class);
    private final LocalTransactions commits = new LocalTransactions();
    private final TransactionTemplate transaction = new TransactionTemplate(commits);
    private final ResolutionPreparation preparation = mock(ResolutionPreparation.class);
    private final ExpectedBinding binding =
        new ExpectedBinding(
            "sub_provider_exact",
            SUBSCRIPTION,
            "cus_provider_exact",
            "pm_provider_exact",
            "price_hosting_exact",
            1L,
            StripeHostingSubscriptionObserver.OperationType.SUBSCRIPTION_PROVISIONING,
            CREATION,
            true);
    private final ObservedSubscription observed = mock(ObservedSubscription.class);
    private final TransitionResult committed =
        new TransitionResult(RESOLUTION, Outcome.COMMITTED, 12L, null);
    private final AtomicReference<Request> preparedRequest = new AtomicReference<>();
    private final AtomicReference<AuthorizedActor> authorizedActor = new AtomicReference<>();
    private final AtomicInteger resolutionWrites = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private boolean failedAttemptCommitted;
    private final AccountHostingSubscriptionTerminalResolutionService service =
        new AccountHostingSubscriptionTerminalResolutionService(
            actors, primaryAuthentication, repository, observer, Clock.fixed(NOW, ZoneOffset.UTC));

    private void currentActors(AccountAuthenticatedActor... currentActors) {
      AtomicInteger calls = new AtomicInteger();
      doAnswer(
              invocation -> {
                int index = calls.getAndIncrement();
                if (index >= currentActors.length) {
                  throw new IllegalStateException("Unexpected current Account actor request");
                }
                int ordinal = index + 1;
                events.add("current-actor-" + ordinal);
                @SuppressWarnings("unchecked")
                Function<AccountAuthenticatedActor, Object> action = invocation.getArgument(1);
                return inOwnerTransaction(
                    () -> {
                      Object result = action.apply(currentActors[index]);
                      events.add("current-account-return-" + ordinal);
                      return result;
                    });
              })
          .when(actors)
          .withCurrentAccount(eq(CONTROL_UI_JWT), any());
    }

    private void currentActorUnavailableOnFirstCall() {
      doAnswer(
              invocation -> {
                events.add("initial-actor-unavailable");
                throw new IllegalStateException("current Account actor unavailable");
              })
          .when(actors)
          .withCurrentAccount(eq(CONTROL_UI_JWT), any());
    }

    private void currentActorAvailableThenUnavailable(AccountAuthenticatedActor currentActor) {
      AtomicInteger calls = new AtomicInteger();
      doAnswer(
              invocation -> {
                if (calls.getAndIncrement() > 0) {
                  events.add("final-actor-unavailable");
                  throw new IllegalStateException("final current Account actor unavailable");
                }
                events.add("current-actor-1");
                @SuppressWarnings("unchecked")
                Function<AccountAuthenticatedActor, Object> action = invocation.getArgument(1);
                return inOwnerTransaction(() -> action.apply(currentActor));
              })
          .when(actors)
          .withCurrentAccount(eq(CONTROL_UI_JWT), any());
    }

    private void prepare(TransitionResult replay) {
      when(preparation.binding()).thenReturn(binding);
      when(preparation.replayResult()).thenReturn(replay);
      doAnswer(invocation -> preparedRequest.get()).when(preparation).request();
      doAnswer(
              invocation -> {
                assertWritableOwnerTransaction();
                Request request = invocation.getArgument(0);
                preparedRequest.set(request);
                events.add("prepare");
                doAnswer(ignored -> request).when(preparation).request();
                return preparation;
              })
          .when(repository)
          .prepareTerminalResolution(any(Request.class));
    }

    private void observeOutsideOwnerTransaction() {
      doAnswer(
              invocation -> {
                assertNoTransactionOrSynchronization();
                assertThat(invocation.getArgument(0, ExpectedBinding.class)).isEqualTo(binding);
                events.add("provider-read");
                return observed;
              })
          .when(observer)
          .observe(any(ExpectedBinding.class));
    }

    private void applyAfterAuthorization(UUID billingOwner) {
      doAnswer(
              invocation -> {
                assertWritableOwnerTransaction();
                events.add("apply");
                ResolutionPreparation prepared = invocation.getArgument(0);
                Authorization authorization = invocation.getArgument(2);
                AuthorizedActor authorized =
                    authorization.requireAuthorized(
                        new LockedTarget(prepared.request(), billingOwner, 11L));
                authorizedActor.set(authorized);
                resolutionWrites.incrementAndGet();
                events.add("resolution-write");
                return committed;
              })
          .when(repository)
          .applyTerminalResolution(
              any(ResolutionPreparation.class), any(), any(Authorization.class));
    }

    private void primaryAuthenticatesAs(UUID accountId) {
      AccountServiceImpl.ControlUiPrimaryIdentity identity = primaryIdentity(accountId);
      doAnswer(
              invocation -> {
                assertThat(invocation.getArgument(0, String.class)).isEqualTo(EMAIL);
                assertThat(invocation.getArgument(1, String.class)).isEqualTo(SECRET);
                assertWritableOwnerTransaction();
                events.add("primary-authentication");
                return identity;
              })
          .when(primaryAuthentication)
          .authenticateControlUiPrimaryIdentity(EMAIL, SECRET);
    }

    private void primaryAuthenticationDenied() {
      doAnswer(
              invocation -> {
                assertWritableOwnerTransaction();
                events.add("primary-authentication");
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                      @Override
                      public void afterCommit() {
                        failedAttemptCommitted = true;
                        events.add("failed-login-attempt-committed");
                      }
                    });
                throw new AuthenticationException(
                    "INVALID_CREDENTIALS", "Rejected credential was " + SECRET);
              })
          .when(primaryAuthentication)
          .authenticateControlUiPrimaryIdentity(EMAIL, SECRET);
    }

    private <T> T inOwnerTransaction(Supplier<T> action) {
      return transaction.execute(status -> action.get());
    }

    private static void assertWritableOwnerTransaction() {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    }

    private static void assertNoTransactionOrSynchronization() {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }
  }

  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
