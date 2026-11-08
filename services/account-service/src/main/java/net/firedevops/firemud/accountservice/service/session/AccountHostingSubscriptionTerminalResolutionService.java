package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.TransitionResult;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.AuthorizedActor;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.Request;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.AccountAuthenticatedActor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed same-billing-owner cancellation recovery. Current Account identity and fresh
 * primary authentication precede the final guarded mutation in its owner transaction. A role,
 * caller-supplied actor, expired observation or provider lookup failure cannot clear the hold. No
 * HTTP route, global intervention, provider cancellation, registration or activation is added.
 */
public final class AccountHostingSubscriptionTerminalResolutionService {
  private final AccountControlUiActorService actors;
  private final AccountServiceImpl primaryAuthentication;
  private final AccountHostingSubscriptionLifecycleRepository repository;
  private final StripeHostingSubscriptionObserver observer;
  private final Clock clock;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected Account owner collaborators are retained privately and never exposed.")
  public AccountHostingSubscriptionTerminalResolutionService(
      AccountControlUiActorService actors,
      AccountServiceImpl primaryAuthentication,
      AccountHostingSubscriptionLifecycleRepository repository,
      StripeHostingSubscriptionObserver observer,
      Clock clock) {
    this.actors = Objects.requireNonNull(actors);
    this.primaryAuthentication = Objects.requireNonNull(primaryAuthentication);
    this.repository = Objects.requireNonNull(repository);
    this.observer = Objects.requireNonNull(observer);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * The input identifies the exact failed owner operation; it supplies no actor or authorization.
   * The final primary-authentication call is purpose-local and immediate, rather than reusing a
   * game-creation reauthentication grant or treating a control-ui token's issue time as step-up.
   */
  public TransitionResult resolve(String controlUiJwt, String email, String secret, Input input) {
    outsideSql();
    Objects.requireNonNull(input, "Exact resolution input is required");
    var prepared =
        actors.withCurrentAccount(
            controlUiJwt, actor -> repository.prepareTerminalResolution(input.request(actor)));
    if (prepared.replayResult() != null) {
      // This is an authenticated exact-result read, not another mutation or reauthentication.
      return prepared.replayResult();
    }
    requireCredentials(email, secret);
    outsideSql();
    var observed = observer.observe(prepared.binding());
    outsideSql();
    Attempt attempt =
        actors.withCurrentAccount(
            controlUiJwt,
            actor -> {
              requireExactActor(prepared.request(), actor);
              try {
                return new Attempt(
                    repository.applyTerminalResolution(
                        prepared,
                        observed,
                        target -> {
                          requireExactActor(target.request(), actor);
                          if (!actor.accountId().equals(target.billingOwnerAccountId())) {
                            throw new FenceRejectedException(
                                "Only the exact current billing-owner subject may resolve");
                          }
                          requireWritableOwnerTransaction();
                          AccountServiceImpl.ControlUiPrimaryIdentity actual;
                          try {
                            actual =
                                primaryAuthentication.authenticateControlUiPrimaryIdentity(
                                    email, secret);
                          } catch (AuthenticationException denied) {
                            // The repository invokes this callback before its first mutation.
                            // Returning the failure from the enclosing transaction preserves real
                            // failed-login accounting without writing a resolution or consuming
                            // OTP.
                            throw new PrimaryAuthenticationDenied(denied.getCode());
                          }
                          if (!actor.accountId().equals(actual.accountId())) {
                            throw new FenceRejectedException(
                                "Primary authentication belongs to another Account");
                          }
                          return new AuthorizedActor(
                              actor.accountId(),
                              actor.issuanceOperationId(),
                              actor.tokenHash(),
                              clock.instant().truncatedTo(ChronoUnit.MICROS));
                        }),
                    null);
              } catch (PrimaryAuthenticationDenied denied) {
                return new Attempt(null, denied.code);
              }
            });
    if (attempt.failureCode != null) {
      throw new AuthenticationException(attempt.failureCode, "Account reauthentication failed");
    }
    return Objects.requireNonNull(attempt.result);
  }

  private static void requireExactActor(Request request, AccountAuthenticatedActor actor) {
    if (!request.actorAccountId().equals(actor.accountId())
        || !request.controlUiIssuanceOperationId().equals(actor.issuanceOperationId())
        || !request.controlUiTokenHash().equals(actor.tokenHash())) {
      throw new FenceRejectedException("Current Account actor differs from the captured operation");
    }
  }

  private static void requireCredentials(String email, String secret) {
    if (email == null
        || email.isBlank()
        || email.length() > 254
        || secret == null
        || secret.isEmpty()
        || secret.length() > 1024) {
      throw new IllegalArgumentException("Bounded fresh Account credentials are required");
    }
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Writable Account owner transaction is required");
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Recovery refuses an ambient transaction or synchronization");
    }
  }

  /** Exact identifiers and reason only; credentials never enter this value or its digest. */
  public record Input(
      UUID resolutionRequestId,
      UUID failedRequestId,
      UUID creationRequestId,
      UUID subscriptionId,
      UUID tenantId,
      long expectedFailedOperationVersion,
      long expectedSubscriptionVersion,
      UUID auditEventId,
      String auditReason) {
    Request request(AccountAuthenticatedActor actor) {
      return new Request(
          resolutionRequestId,
          failedRequestId,
          creationRequestId,
          subscriptionId,
          tenantId,
          expectedFailedOperationVersion,
          expectedSubscriptionVersion,
          actor.accountId(),
          actor.issuanceOperationId(),
          actor.tokenHash(),
          auditEventId,
          auditReason);
    }
  }

  private record Attempt(TransitionResult result, String failureCode) {}

  private static final class PrimaryAuthenticationDenied extends RuntimeException {
    private final String code;

    private PrimaryAuthenticationDenied(String code) {
      super("Account primary authentication denied");
      this.code = code;
    }
  }
}
