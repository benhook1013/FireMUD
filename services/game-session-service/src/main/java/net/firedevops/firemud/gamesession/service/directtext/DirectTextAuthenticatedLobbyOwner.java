package net.firedevops.firemud.gamesession.service.directtext;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.DirectTextIssuedConnectScope;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.repository.DirectTextAuthenticatedLobbyRepository;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextState;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed, unregistered public-only lobby preparation. It borrows the current verified
 * private LOGIN result on every operation, never stores it on the service, and never calls
 * Authenticate, performs cleanup, selects an actor, binds gameplay or publishes wire success.
 */
public final class DirectTextAuthenticatedLobbyOwner {
  private final DirectTextAuthenticatedLobbyRepository repository;
  private final DirectTextWorldSelectionService selections;
  private final AccountClient account;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public DirectTextAuthenticatedLobbyOwner(
      DirectTextAuthenticatedLobbyRepository repository,
      DirectTextWorldSelectionService selections,
      AccountClient account,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository);
    this.selections = Objects.requireNonNull(selections);
    this.account = Objects.requireNonNull(account);
    this.clock = Objects.requireNonNull(clock);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * One fresh explicit REALMS intent, from this selection owner's genuine guarded public handle.
   * Scope issuance has no recoverable request identity: an unknown response is never reissued.
   * Returned evidence is internal composition data, not a client-facing response.
   */
  public DirectTextAuthenticatedLobbyScope realms(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      DirectTextAuthenticatedLobbyCustody custody,
      DirectTextWorldSelectionService.SelectionSnapshot menu,
      String selector) {
    requireNoTransaction();
    return current(
        original,
        verified,
        custody,
        transport -> {
          transaction.executeWithoutResult(status -> repository.verifyOriginal(original, verified));
          var target =
              selections
                  .resolve(menu, selector)
                  .orElseThrow(DirectTextAuthenticatedLobbyOwner::unavailable);
          var accountTarget = DirectTextAuthenticatedLobbyScope.targetFor(target);
          UUID scopeRecordId = distinctId(original, verified, transport, null);
          UUID scopeRequestId = distinctId(original, verified, transport, scopeRecordId);
          var context =
              DirectTextAuthenticatedLobbyJoin.contextFor(
                  verified.evidence().accountId().toString(),
                  transport,
                  accountTarget,
                  scopeRequestId);
          DirectTextAuthenticatedLobbyScope.requireLiveResult(original, verified, clock);
          // Account call is outside SQL. No fallback, retry, private/playtest branch or arbitrary
          // target.
          var response = account.issueDirectTextConnectScope(context, accountTarget);
          if (response == null
              || response.getSerializedSize() > 8192
              || !response.getUnknownFields().asMap().isEmpty()) throw unavailable();
          var issued =
              DirectTextIssuedConnectScope.fromAccountResponse(
                  verified.evidence().accountId().toString(), accountTarget, response, clock);
          var scope =
              new DirectTextAuthenticatedLobbyScope(
                  scopeRecordId,
                  InitialGameplayLoginContextState.installed(original, verified),
                  transport,
                  target,
                  issued);
          scope.requireCurrent(original, verified, transport, clock);
          transaction.executeWithoutResult(
              status -> repository.retainScope(scope, verified, clock));
          var committed = repository.readCommittedScope(scope);
          committed.requireCurrent(original, verified, transport, clock);
          return committed;
        });
  }

  /**
   * Explicit JOIN of the retained CURRENT scope only. Concurrent or interrupted continuations use
   * the same committed request. Only the single dispatch-claim winner calls Account; neither this
   * owner nor a replacement owner re-arms a dispatch-possible original. AccountClient's bounded
   * same-invocation retry uses the identical frozen request, not another logical attempt.
   */
  public Observation join(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      DirectTextAuthenticatedLobbyCustody custody) {
    requireNoTransaction();
    var frozen =
        current(
            original,
            verified,
            custody,
            transport ->
                transaction.execute(
                    status -> repository.freezeCurrentJoin(original, verified, transport, clock)));
    var committed = repository.readCommittedJoin(Objects.requireNonNull(frozen));
    boolean claimed =
        current(
            original,
            verified,
            custody,
            transport ->
                Boolean.TRUE.equals(
                    transaction.execute(
                        status ->
                            repository.claimDispatch(committed, verified, transport, clock))));
    if (!claimed) return observe(committed, original, verified, custody);

    // An ambiguous claim COMMIT/readback throws before Account. The persisted possible state is
    // irreversible even if no RPC actually happened; later continuations cannot invent rollback.
    var possible = repository.readCommittedJoin(committed);
    if (possible.state() != DirectTextAuthenticatedLobbyJoin.State.DISPATCH_POSSIBLE)
      throw unavailable();
    JoinPublicProductionMembershipResponse response =
        current(
            original,
            verified,
            custody,
            transport -> {
              possible.scope().requireCurrent(original, verified, transport, clock);
              transaction.executeWithoutResult(
                  status -> {
                    var selected = repository.current(original, verified, transport, clock);
                    if (!selected.recordId().equals(possible.scope().recordId()))
                      throw unavailable();
                  });
              var request = possible.request();
              return account.joinPublicProductionMembership(
                  request.getPlayerContext(),
                  request.getConnectScopeId(),
                  request.getRequestId(),
                  possible.scope().issued().connectScopeExpiresAtInstant());
            });
    try {
      DirectTextAuthenticatedLobbyJoin.requirePositiveReply(possible.scope(), response);
    } catch (IllegalArgumentException unknown) {
      // Includes every generic local/transport/preflight error. No false terminal receipt.
      return observe(possible, original, verified, custody);
    }
    // A positively correlated in-flight reply remains retainable even if custody is now lost or
    // the original abandoned. This is historical SQL persistence, NOT reply application.
    transaction.executeWithoutResult(
        status -> repository.recordPositiveReply(possible, verified, response));
    var retained = repository.readCommittedJoin(possible);
    if (!retained.reply().filter(response::equals).isPresent()) throw unavailable();
    return observe(retained, original, verified, custody);
  }

  private Observation observe(
      DirectTextAuthenticatedLobbyJoin originalJoin,
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      DirectTextAuthenticatedLobbyCustody custody) {
    var retained = repository.readCommittedJoin(originalJoin);
    try {
      return current(
          original,
          verified,
          custody,
          transport -> {
            var selected =
                transaction.execute(
                    status -> repository.current(original, verified, transport, clock));
            if (selected == null || !selected.recordId().equals(retained.scope().recordId()))
              throw unavailable();
            retained.scope().requireCurrent(original, verified, transport, clock);
            return new Observation(
                retained.scope().recordId(),
                retained.requestId(),
                retained.reply().isPresent()
                    ? Status.COMPLETED_CURRENT_NON_ADMITTING
                    : Status.RETAINED_UNRESOLVED,
                retained.reply());
          });
    } catch (RuntimeException noCurrentAuthority) {
      return new Observation(
          retained.scope().recordId(),
          retained.requestId(),
          Status.HISTORICAL_ONLY,
          Optional.empty());
    }
  }

  private <T> T current(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      DirectTextAuthenticatedLobbyCustody custody,
      Function<ObservedDirectTextTransport, T> action) {
    DirectTextAuthenticatedLobbyScope.requireLiveResult(original, verified, clock);
    return Objects.requireNonNull(custody)
        .withCurrent(
            original,
            verified,
            transport -> {
              Objects.requireNonNull(transport);
              DirectTextAuthenticatedLobbyScope.requireLiveResult(original, verified, clock);
              return action.apply(transport);
            });
  }

  private static UUID distinctId(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      ObservedDirectTextTransport transport,
      UUID other) {
    UUID id;
    do {
      id = UUID.randomUUID();
    } while (id.equals(original.requestId())
        || id.equals(original.callerContextId())
        || id.equals(transport.proxyConnectionId())
        || id.equals(verified.evidence().tokenJti())
        || id.equals(other));
    return id;
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Current authenticated direct-text lobby custody is unavailable");
  }

  public enum Status {
    RETAINED_UNRESOLVED,
    COMPLETED_CURRENT_NON_ADMITTING,
    HISTORICAL_ONLY
  }

  /**
   * Historical membership observation only; currentReply is AS-OF this protected call's final
   * selection check. It cannot authenticate, install a binding, or authorize actor selection/PLAY.
   */
  public record Observation(
      UUID scopeRecordId,
      UUID requestId,
      Status status,
      Optional<JoinPublicProductionMembershipResponse> currentReply) {}
}
