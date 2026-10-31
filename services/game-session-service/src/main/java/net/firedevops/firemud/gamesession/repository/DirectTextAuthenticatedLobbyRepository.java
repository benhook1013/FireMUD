package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyJoin;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyJoin.State;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered companion owner. Writes require the caller's writable READ COMMITTED transaction;
 * independent primary readback is mandatory before remote effects. Protected DB delivery and
 * current authenticated custody belong to composition, never to decoded SQL evidence.
 */
public final class DirectTextAuthenticatedLobbyRepository {
  public static final String SCOPE = "game_session_direct_text_authenticated_lobby_scope";
  public static final String CURRENT = "game_session_direct_text_authenticated_lobby_current";
  public static final String JOIN = "game_session_direct_text_authenticated_lobby_join";
  private static final String ORIGINAL = "game_session_initial_gameplay_login_journal";
  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The injected transaction-bound DSLContext must remain shared with its owner")
  public DirectTextAuthenticatedLobbyRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  /** Serializes with actual parent abandonment, not a companion boolean or an unlocked snapshot. */
  public void verifyOriginal(
      InitialGameplayLoginAttemptCoordinates original, CanonicalGameplayLoginResult verified) {
    lockOriginal(original, verified, false);
  }

  public void retainScope(
      DirectTextAuthenticatedLobbyScope scope, CanonicalGameplayLoginResult verified, Clock clock) {
    lockOriginal(scope.original(), verified, false);
    requireEvidence(scope, verified);
    scope.requireCurrent(scope.original(), verified, scope.transport(), clock);
    byte[] bytes = scope.encode();
    var e = verified.evidence();
    dsl.execute(
        "INSERT INTO "
            + SCOPE
            + " (scope_record_id, original_request_id,"
            + " original_caller_context_id, original_attempt_fence, account_id, proxy_connection_id,"
            + " transport_session_id, verified_token_jti, verified_token_sha256,"
            + " verified_token_generation, verified_issuance_fence, scope_bytes)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        scope.recordId(),
        scope.original().requestId(),
        scope.original().callerContextId(),
        scope.original().expectedAttemptFence(),
        e.accountId(),
        scope.transport().proxyConnectionId(),
        scope.transport().transportSessionId(),
        e.tokenJti(),
        e.tokenSha256(),
        Long.parseLong(e.tokenGeneration()),
        Long.parseLong(e.issuanceFence()),
        bytes);
    var retained = requiredScope(dsl, scope.recordId());
    requireExact(scope, retained);
    dsl.execute(
        "INSERT INTO "
            + CURRENT
            + " (original_request_id, scope_record_id, scope_sequence)"
            + " SELECT original_request_id, scope_record_id, scope_sequence FROM "
            + SCOPE
            + " WHERE scope_record_id = ? ON CONFLICT (original_request_id) DO UPDATE"
            + " SET scope_record_id = EXCLUDED.scope_record_id, scope_sequence = EXCLUDED.scope_sequence",
        scope.recordId());
  }

  public DirectTextAuthenticatedLobbyScope current(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      ObservedDirectTextTransport transport,
      Clock clock) {
    lockOriginal(original, verified, false);
    Record row =
        dsl.fetchOne(
            "SELECT s.* FROM "
                + SCOPE
                + " s JOIN "
                + CURRENT
                + " c ON c.scope_record_id = s.scope_record_id WHERE c.original_request_id = ?",
            original.requestId());
    if (row == null) throw unavailable();
    var scope = decodeScope(row);
    scope.requireCurrent(original, verified, transport, clock);
    return scope;
  }

  /** Exactly one attempt per retained scope; concurrent continuations read the winner's request. */
  public DirectTextAuthenticatedLobbyJoin freezeCurrentJoin(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      ObservedDirectTextTransport transport,
      Clock clock) {
    var scope = current(original, verified, transport, clock);
    Record prior = joinRow(dsl, scope.recordId());
    if (prior != null) return decodeJoin(scope, prior);
    UUID requestId;
    do {
      requestId = UUID.randomUUID();
    } while (requestId.equals(original.requestId())
        || requestId.equals(original.callerContextId())
        || requestId.equals(scope.recordId())
        || requestId.equals(transport.proxyConnectionId())
        || requestId.equals(verified.evidence().tokenJti()));
    var frozen =
        new DirectTextAuthenticatedLobbyJoin(
            scope,
            DirectTextAuthenticatedLobbyJoin.requestFor(scope, requestId),
            State.NEVER_DISPATCHED,
            Optional.empty());
    dsl.execute(
        "INSERT INTO "
            + JOIN
            + " (scope_record_id, join_request_id, request_bytes, state) VALUES (?, ?, ?, 'NEVER_DISPATCHED')",
        scope.recordId(),
        requestId,
        frozen.request().toByteArray());
    return decodeJoin(scope, Objects.requireNonNull(joinRow(dsl, scope.recordId())));
  }

  /** One irreversible authorization. A false result NEVER permits an additional Account call. */
  public boolean claimDispatch(
      DirectTextAuthenticatedLobbyJoin expected,
      CanonicalGameplayLoginResult verified,
      ObservedDirectTextTransport transport,
      Clock clock) {
    var scope = current(expected.scope().original(), verified, transport, clock);
    requireExact(expected.scope(), scope);
    var retained = decodeJoin(scope, Objects.requireNonNull(joinRow(dsl, scope.recordId())));
    requireSameRequest(expected, retained);
    if (retained.state() != State.NEVER_DISPATCHED) return false;
    if (dsl.execute(
            "UPDATE "
                + JOIN
                + " SET state = 'DISPATCH_POSSIBLE' WHERE scope_record_id = ? AND state = 'NEVER_DISPATCHED'",
            scope.recordId())
        != 1) throw unavailable();
    return true;
  }

  /**
   * Historical retention only. Exact in-flight success can survive abandonment, custody loss,
   * expiry and a newer selection; none of these writes applies a result to a transport.
   */
  public void recordPositiveReply(
      DirectTextAuthenticatedLobbyJoin expected,
      CanonicalGameplayLoginResult verified,
      JoinPublicProductionMembershipResponse reply) {
    var scope = expected.scope();
    lockOriginal(scope.original(), verified, true);
    requireEvidence(scope, verified);
    DirectTextAuthenticatedLobbyJoin.requirePositiveReply(scope, reply);
    var retainedScope = requiredScope(dsl, scope.recordId());
    requireExact(scope, retainedScope);
    var retained =
        decodeJoin(retainedScope, Objects.requireNonNull(joinRow(dsl, scope.recordId())));
    requireSameRequest(expected, retained);
    if (retained.state() == State.COMPLETED) {
      if (!retained.reply().filter(reply::equals).isPresent()) throw unavailable();
      return;
    }
    if (retained.state() != State.DISPATCH_POSSIBLE) throw unavailable();
    dsl.execute(
        "UPDATE " + JOIN + " SET state = 'COMPLETED', reply_bytes = ? WHERE scope_record_id = ?",
        reply.toByteArray(),
        scope.recordId());
  }

  public DirectTextAuthenticatedLobbyScope readCommittedScope(
      DirectTextAuthenticatedLobbyScope expected) {
    requireNoTransaction();
    return dsl.connectionResult(
        connection -> {
          requireConnection(connection, false);
          var result =
              requiredScope(DSL.using(connection, SQLDialect.POSTGRES), expected.recordId());
          requireExact(expected, result);
          return result;
        });
  }

  public DirectTextAuthenticatedLobbyJoin readCommittedJoin(
      DirectTextAuthenticatedLobbyJoin expected) {
    requireNoTransaction();
    return dsl.connectionResult(
        connection -> {
          requireConnection(connection, false);
          var reader = DSL.using(connection, SQLDialect.POSTGRES);
          var scope = requiredScope(reader, expected.scope().recordId());
          requireExact(expected.scope(), scope);
          Record row = joinRow(reader, scope.recordId());
          if (row == null) throw unavailable();
          var result = decodeJoin(scope, row);
          requireSameRequest(expected, result);
          return result;
        });
  }

  private void lockOriginal(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      boolean allowAbandoned) {
    requireMutation();
    var e = Objects.requireNonNull(verified).evidence();
    if (!original.requestId().equals(e.requestId())
        || !original.callerContextId().equals(e.callerContextId())) throw unavailable();
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + ORIGINAL + " WHERE request_id = ? FOR UPDATE", original.requestId());
    if (row == null
        || !original.callerContextId().equals(row.get("caller_context_id", UUID.class))
        || !Long.valueOf(original.expectedAttemptFence())
            .equals(row.get("expected_attempt_fence", Long.class))
        || row.get("abandoned", Boolean.class) == null
        || !allowAbandoned && !Boolean.FALSE.equals(row.get("abandoned", Boolean.class))
        || !e.tokenJti().equals(row.get("verified_token_jti", UUID.class))
        || !e.tokenSha256().equals(row.get("verified_token_sha256", String.class))
        || !Long.valueOf(Long.parseLong(e.tokenGeneration()))
            .equals(row.get("verified_token_generation", Long.class))
        || !Long.valueOf(Long.parseLong(e.issuanceFence()))
            .equals(row.get("verified_issuance_fence", Long.class))) throw unavailable();
  }

  private static DirectTextAuthenticatedLobbyScope requiredScope(DSLContext reader, UUID id) {
    Record row = reader.fetchOne("SELECT * FROM " + SCOPE + " WHERE scope_record_id = ?", id);
    if (row == null) throw unavailable();
    return decodeScope(row);
  }

  private static DirectTextAuthenticatedLobbyScope decodeScope(Record row) {
    var scope = DirectTextAuthenticatedLobbyScope.decode(row.get("scope_bytes", byte[].class));
    var e = scope.evidence();
    if (!scope.recordId().equals(row.get("scope_record_id", UUID.class))
        || !scope.original().requestId().equals(row.get("original_request_id", UUID.class))
        || !scope
            .original()
            .callerContextId()
            .equals(row.get("original_caller_context_id", UUID.class))
        || !Long.valueOf(scope.original().expectedAttemptFence())
            .equals(row.get("original_attempt_fence", Long.class))
        || !e.accountId().equals(row.get("account_id", UUID.class))
        || !scope.transport().proxyConnectionId().equals(row.get("proxy_connection_id", UUID.class))
        || !scope
            .transport()
            .transportSessionId()
            .equals(row.get("transport_session_id", String.class))
        || !e.tokenJti().equals(row.get("verified_token_jti", UUID.class))
        || !e.tokenSha256().equals(row.get("verified_token_sha256", String.class))
        || !Long.valueOf(Long.parseLong(e.tokenGeneration()))
            .equals(row.get("verified_token_generation", Long.class))
        || !Long.valueOf(Long.parseLong(e.issuanceFence()))
            .equals(row.get("verified_issuance_fence", Long.class))) throw unavailable();
    return scope;
  }

  private static Record joinRow(DSLContext reader, UUID scopeId) {
    return reader.fetchOne("SELECT * FROM " + JOIN + " WHERE scope_record_id = ?", scopeId);
  }

  private static DirectTextAuthenticatedLobbyJoin decodeJoin(
      DirectTextAuthenticatedLobbyScope scope, Record row) {
    var request =
        DirectTextAuthenticatedLobbyJoin.decodeRequest(row.get("request_bytes", byte[].class));
    byte[] bytes = row.get("reply_bytes", byte[].class);
    var result =
        new DirectTextAuthenticatedLobbyJoin(
            scope,
            request,
            State.valueOf(row.get("state", String.class)),
            bytes == null
                ? Optional.empty()
                : Optional.of(DirectTextAuthenticatedLobbyJoin.decodeReply(scope, bytes)));
    if (!scope.recordId().equals(row.get("scope_record_id", UUID.class))
        || !result.requestId().equals(row.get("join_request_id", UUID.class))) throw unavailable();
    return result;
  }

  private static void requireEvidence(
      DirectTextAuthenticatedLobbyScope scope, CanonicalGameplayLoginResult verified) {
    if (!scope.evidence().equals(verified.evidence())) throw unavailable();
  }

  private static void requireExact(
      DirectTextAuthenticatedLobbyScope expected, DirectTextAuthenticatedLobbyScope actual) {
    if (!Arrays.equals(expected.encode(), actual.encode())) throw unavailable();
  }

  private static void requireSameRequest(
      DirectTextAuthenticatedLobbyJoin expected, DirectTextAuthenticatedLobbyJoin actual) {
    if (!expected.request().equals(actual.request())) throw unavailable();
  }

  private void requireMutation() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
      throw unavailable();
    dsl.connectionResult(
        connection -> {
          requireConnection(connection, true);
          return null;
        });
  }

  private static void requireConnection(Connection connection, boolean mutation)
      throws SQLException {
    if (connection.getAutoCommit() == mutation
        || connection.isReadOnly()
        || mutation
            && connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
      throw unavailable();
    Record settings =
        DSL.using(connection, SQLDialect.POSTGRES)
            .fetchOne(
                "SELECT pg_is_in_recovery() AS recovery, current_setting('synchronous_commit') AS durability");
    if (settings == null
        || !Boolean.FALSE.equals(settings.get("recovery", Boolean.class))
        || !List.of("on", "remote_write", "remote_apply")
            .contains(settings.get("durability", String.class))) throw unavailable();
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact retained direct-text lobby state is unavailable");
  }
}
