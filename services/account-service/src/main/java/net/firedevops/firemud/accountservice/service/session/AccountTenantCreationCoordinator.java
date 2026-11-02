package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.GameDesignAuthorizedTenantCreationClient;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService.CapturedEnvironmentBoundary;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationDispatchRepository.Original;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.OriginalIntent;
import net.firedevops.firemud.accountservice.service.session.AccountTenantCreationIntentRepository.Request;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered bounded creation orchestration; unknown delivery never authorizes another send. */
final class AccountTenantCreationCoordinator {
  private final AccountTenantCreationAdmissionOwner admission;
  private final AccountTenantCreationDispatchRepository dispatches;
  private final GameDesignAuthorizedTenantCreationClient creation;
  private final GameDesignOriginalTenantCreationOutcomeClient outcomes;
  private final AccountTenantCreationCompletionOwner completion;
  private final AccountTenantCreationSourceOrderRepository orders;
  private final TransactionTemplate transactions;

  AccountTenantCreationCoordinator(
      AccountTenantCreationAdmissionOwner admission,
      AccountTenantCreationDispatchRepository dispatches,
      GameDesignAuthorizedTenantCreationClient creation,
      GameDesignOriginalTenantCreationOutcomeClient outcomes,
      AccountTenantCreationCompletionOwner completion,
      AccountTenantCreationSourceOrderRepository orders,
      PlatformTransactionManager manager) {
    this.admission = Objects.requireNonNull(admission);
    this.dispatches = Objects.requireNonNull(dispatches);
    this.creation = Objects.requireNonNull(creation);
    this.outcomes = Objects.requireNonNull(outcomes);
    this.completion = Objects.requireNonNull(completion);
    this.orders = Objects.requireNonNull(orders);
    transactions = new TransactionTemplate(Objects.requireNonNull(manager));
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(5);
  }

  Result start(
      String currentAccountJwt,
      Request request,
      AccountRecentReauthenticationEvidence recent,
      CapturedEnvironmentBoundary environment) {
    outsideSql();
    Objects.requireNonNull(request);
    if (dispatches.hasClaim(request.creationOperationId())) {
      Original original = dispatches.readOriginal(request.creationOperationId());
      requireExactRequest(original.intent(), request);
      return reconcile(original, null, recent, environment);
    }
    var prepared = admission.start(currentAccountJwt, request, recent, environment);
    var claim = dispatches.claim(prepared, environment, recent);
    if (claim.isEmpty()) return recover(request.creationOperationId(), recent, environment);
    var initial = claim.orElseThrow();
    Original original = initial.original();
    requireExactRequest(original.intent(), request);
    FreshTenantCreationEvidence response = null;
    try {
      response =
          creation.create(
              initial.callId(),
              original.binding(),
              original.intent().sourceGameTenantKey(),
              original.intent().name(),
              original.intent().description(),
              initial.validBefore());
    } catch (StatusRuntimeException | IllegalStateException | IllegalArgumentException uncertain) {
      // The immutable claim remains. Independent original readback, never a resend, resolves
      // delivery.
    }
    return reconcile(original, response, recent, environment);
  }

  /** Terminal read first; no JWT, re-admission, missing-receipt creation, or dispatch branch. */
  Result recover(
      UUID operation,
      AccountRecentReauthenticationEvidence recent,
      CapturedEnvironmentBoundary environment) {
    outsideSql();
    return reconcile(dispatches.readOriginal(operation), null, recent, environment);
  }

  private Result reconcile(
      Original original,
      FreshTenantCreationEvidence response,
      AccountRecentReauthenticationEvidence recent,
      CapturedEnvironmentBoundary environment) {
    VerifiedTerminal terminal = readOrCloseOriginal(original);
    if (terminal == null) return new Result(State.PENDING, null, null);
    if (!original.binding().equals(terminal.binding())) throw inconsistent();
    if (terminal.evidence().outcome() == Outcome.NOT_CREATED) {
      if (response != null) throw inconsistent();
      settleNegative(original, terminal);
      return new Result(State.NOT_CREATED, null, null);
    }
    if (terminal.evidence().outcome() != Outcome.CREATED
        || response != null && !terminal.evidence().receipt().filter(response::equals).isPresent())
      throw inconsistent();
    // The final owner revalidates actual current authority/finite bounds, or replays its exact
    // result.
    // A late historical CREATED result cannot bypass those checks or consume new authority here.
    var result =
        completion.complete(original.intent().creationOperationId(), terminal, environment, recent);
    return new Result(State.COMPLETED, result.payload(), result.digest());
  }

  private VerifiedTerminal readOrCloseOriginal(Original original) {
    try {
      return outcomes.readOriginal(original.binding());
    } catch (StatusRuntimeException initial) {
      Status.Code code = initial.getStatus().getCode();
      if (code != Status.Code.NOT_FOUND && code != Status.Code.FAILED_PRECONDITION) return null;
    } catch (IllegalStateException uncertainRead) {
      return null;
    }
    // Absence is not evidence. Explicitly serialize future-create exclusion at the GD owner.
    try {
      creation.closeOriginal(original.binding());
    } catch (StatusRuntimeException | IllegalStateException uncertainClosure) {
      // Creation may have won, or the close may have committed despite losing its reply.
    }
    try {
      return outcomes.readOriginal(original.binding());
    } catch (StatusRuntimeException | IllegalStateException uncertainReadback) {
      return null;
    }
  }

  private void settleNegative(Original original, VerifiedTerminal terminal) {
    transactions.executeWithoutResult(
        status -> {
          var actual =
              orders
                  .readOriginal(original.intent().creationOperationId())
                  .orElseThrow(AccountTenantCreationCoordinator::inconsistent);
          if (!Arrays.equals(actual.canonicalBytes(), original.holder().canonicalBytes())
              || !actual.outcomeBinding().equals(terminal.binding())) throw inconsistent();
          orders.settleLocked(actual, terminal);
        });
    transactions.executeWithoutResult(
        status -> {
          var actual =
              orders
                  .readSettlement(original.intent().creationOperationId())
                  .orElseThrow(AccountTenantCreationCoordinator::inconsistent);
          if (!Arrays.equals(actual.canonicalBytes(), terminal.evidence().canonicalBytes()))
            throw inconsistent();
        });
  }

  private static void requireExactRequest(OriginalIntent original, Request request) {
    if (!original.requestId().equals(request.requestId())
        || !original.creationOperationId().equals(request.creationOperationId())
        || !original.gameDesignCreationRequestId().equals(request.gameDesignCreationRequestId())
        || !original.initiatingAccountId().equals(request.initiatingAccountId())
        || !original.targetNamespace().equals(request.targetNamespace())
        || !original.sourceGameTenantKey().equals(request.sourceGameTenantKey())
        || !original.name().equals(request.name())
        || !Objects.equals(original.description(), request.description())
        || !original
            .recentReauthenticationEvidenceId()
            .equals(request.recentReauthenticationEvidenceId())
        || !original.creatorPartyId().equals(request.creatorPartyId())
        || !original.consentOperationId().equals(request.consentOperationId())
        || original.consentStatementVersion() != request.consentStatementVersion()
        || original.affirmativeConsent() != request.affirmativeConsent()) throw inconsistent();
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Creation coordinator must start outside SQL");
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException("Exact original tenant-creation outcome is inconsistent");
  }

  enum State {
    PENDING,
    NOT_CREATED,
    COMPLETED
  }

  static final class Result {
    private final State state;
    private final byte[] payload;
    private final String digest;

    private Result(State state, byte[] payload, String digest) {
      this.state = state;
      this.payload = payload == null ? null : payload.clone();
      this.digest = digest;
    }

    State state() {
      return state;
    }

    byte[] payload() {
      return payload == null ? null : payload.clone();
    }

    String digest() {
      return digest;
    }

    @Override
    public String toString() {
      return "AccountTenantCreationCoordinator.Result[" + state + "]";
    }
  }
}
