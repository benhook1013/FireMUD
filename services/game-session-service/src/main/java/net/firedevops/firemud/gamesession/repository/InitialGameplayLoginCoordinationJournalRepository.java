package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness;
import net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.Disposition;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginDisposalResult.ProofKind;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginJournalSnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered GS PostgreSQL lifecycle journal. Mutation results are uncommitted until the caller's
 * transaction completes; readCommitted must follow before any relevant remote dispatch. There is no
 * Authenticate request, credential recovery, installation, retention cleanup or authority API.
 */
public final class InitialGameplayLoginCoordinationJournalRepository {
  private static final String TABLE = "game_session_initial_gameplay_login_journal";
  private final DSLContext dsl;

  public InitialGameplayLoginCoordinationJournalRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  public InitialGameplayLoginJournalSnapshot reserve(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    Objects.requireNonNull(coordinates, "coordinates");
    requireMutation();
    try {
      dsl.execute(
          "INSERT INTO "
              + TABLE
              + " (request_id, caller_context_id, expected_attempt_fence) "
              + "VALUES (?, ?, ?) ON CONFLICT (request_id) DO NOTHING",
          coordinates.requestId(),
          coordinates.callerContextId(),
          coordinates.expectedAttemptFence());
    } catch (DataAccessException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw new JournalConflictException();
      }
      throw failure;
    }
    return locked(coordinates);
  }

  /** Accepts only the privately constructed, validated result, never its public evidence record. */
  public InitialGameplayLoginJournalSnapshot recordVerifiedResult(
      InitialGameplayLoginAttemptCoordinates coordinates, CanonicalGameplayLoginResult result) {
    Objects.requireNonNull(result, "result");
    var evidence = result.evidence();
    if (!coordinates.requestId().equals(evidence.requestId())
        || !coordinates.callerContextId().equals(evidence.callerContextId())) {
      throw new JournalConflictException();
    }
    var witness =
        InitialGameplayLoginTokenWitness.newBuilder()
            .setTokenJti(evidence.tokenJti().toString())
            .setTokenSha256(evidence.tokenSha256())
            .setTokenGeneration(Long.parseLong(evidence.tokenGeneration()))
            .setIssuanceFence(Long.parseLong(evidence.issuanceFence()))
            .build();
    InitialGameplayLoginJournalSnapshot.request(coordinates, witness);
    requireMutation();
    var retained = locked(coordinates);
    if (retained.verifiedWitness().isPresent()) {
      if (!retained.verifiedWitness().orElseThrow().equals(witness)) {
        throw new JournalConflictException();
      }
      return retained;
    }
    // This append remains allowed after abandonment/freeze; it cannot upgrade the frozen request.
    dsl.execute(
        "UPDATE "
            + TABLE
            + " SET verified_token_jti = ?, verified_token_sha256 = ?, "
            + "verified_token_generation = ?, verified_issuance_fence = ? WHERE request_id = ?",
        evidence.tokenJti(),
        witness.getTokenSha256(),
        witness.getTokenGeneration(),
        witness.getIssuanceFence(),
        coordinates.requestId());
    return locked(coordinates);
  }

  public InitialGameplayLoginJournalSnapshot abandon(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    requireMutation();
    var retained = locked(coordinates);
    if (!retained.abandoned()) {
      dsl.execute(
          "UPDATE "
              + TABLE
              + " SET abandoned = TRUE, abandoned_at = clock_timestamp() WHERE request_id = ?",
          coordinates.requestId());
    }
    return locked(coordinates);
  }

  /** Freeze witness presence OR absence once; a lost first dispatch cannot change Account input. */
  public InitialGameplayLoginJournalSnapshot freezeRetirementRequest(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    requireMutation();
    var retained = locked(coordinates);
    if (!retained.abandoned()) {
      throw new JournalConflictException();
    }
    if (retained.frozenRetirementRequest().isEmpty()) {
      var request =
          InitialGameplayLoginJournalSnapshot.request(
              coordinates, retained.verifiedWitness().orElse(null));
      dsl.execute(
          "UPDATE " + TABLE + " SET retirement_request_bytes = ? WHERE request_id = ?",
          request.toByteArray(),
          coordinates.requestId());
    }
    return locked(coordinates);
  }

  /**
   * Retained receipt is historical lifecycle evidence, never present Redis-marker qualification.
   */
  public InitialGameplayLoginJournalSnapshot recordDisposalReceipt(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginDisposalResult receipt) {
    Objects.requireNonNull(receipt, "receipt");
    if (!coordinates.requestId().equals(receipt.requestId())
        || !coordinates.callerContextId().equals(receipt.callerContextId())) {
      throw new JournalConflictException();
    }
    requireMutation();
    var retained = locked(coordinates);
    if (!retained.abandoned() || retained.frozenRetirementRequest().isEmpty()) {
      throw new JournalConflictException();
    }
    retained
        .retainedReceipt()
        .ifPresent(
            previous -> {
              if (!previous.disposalOperationId().equals(receipt.disposalOperationId())
                  || previous.disposition() != Disposition.PENDING && !previous.equals(receipt)) {
                throw new JournalConflictException();
              }
            });
    if (!retained.retainedReceipt().filter(receipt::equals).isPresent()) {
      dsl.execute(
          "UPDATE "
              + TABLE
              + " SET disposal_operation_id = ?, disposal_disposition = ?, "
              + "disposal_proof_kind = ? WHERE request_id = ?",
          receipt.disposalOperationId(),
          receipt.disposition().name(),
          receipt.proofKind().name(),
          coordinates.requestId());
    }
    return locked(coordinates);
  }

  /** Reads exact original coordinates from the durable primary after the owner COMMIT. */
  public Optional<InitialGameplayLoginJournalSnapshot> readCommitted(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    Objects.requireNonNull(coordinates, "coordinates");
    requireNoTransaction();
    Record row =
        dsl.connectionResult(
            connection -> {
              requireConnection(connection, false);
              return DSL.using(connection, SQLDialect.POSTGRES)
                  .fetchOne(
                      "SELECT * FROM " + TABLE + " WHERE request_id = ?", coordinates.requestId());
            });
    if (row == null) {
      return Optional.empty();
    }
    var snapshot = decode(row);
    requireExact(coordinates, snapshot);
    return Optional.of(snapshot);
  }

  /** Bounded local inventory, including reserved rows whose transient secrets were lost. */
  public List<InitialGameplayLoginJournalSnapshot> readRetainedPage(
      Optional<UUID> afterRequestId, int limit) {
    Objects.requireNonNull(afterRequestId, "afterRequestId");
    if (limit <= 0 || limit > 128) {
      throw new IllegalArgumentException("LOGIN journal page size must be between 1 and 128");
    }
    requireNoTransaction();
    return dsl.connectionResult(
        connection -> {
          requireConnection(connection, false);
          var reader = DSL.using(connection, SQLDialect.POSTGRES);
          var rows =
              afterRequestId.isPresent()
                  ? reader.fetch(
                      "SELECT * FROM "
                          + TABLE
                          + " WHERE request_id > ? ORDER BY request_id LIMIT ?",
                      afterRequestId.orElseThrow(),
                      limit)
                  : reader.fetch("SELECT * FROM " + TABLE + " ORDER BY request_id LIMIT ?", limit);
          return rows.stream()
              .map(InitialGameplayLoginCoordinationJournalRepository::decode)
              .toList();
        });
  }

  private InitialGameplayLoginJournalSnapshot locked(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE request_id = ? FOR UPDATE", coordinates.requestId());
    if (row == null) {
      throw new JournalUnavailableException();
    }
    var snapshot = decode(row);
    requireExact(coordinates, snapshot);
    return snapshot;
  }

  private static InitialGameplayLoginJournalSnapshot decode(Record row) {
    try {
      var coordinates =
          new InitialGameplayLoginAttemptCoordinates(
              required(row, "request_id", UUID.class),
              required(row, "caller_context_id", UUID.class),
              required(row, "expected_attempt_fence", Long.class));
      UUID tokenJti = row.get("verified_token_jti", UUID.class);
      String hash = row.get("verified_token_sha256", String.class);
      Long generation = row.get("verified_token_generation", Long.class);
      Long fence = row.get("verified_issuance_fence", Long.class);
      Optional<InitialGameplayLoginTokenWitness> witness = Optional.empty();
      if (tokenJti != null || hash != null || generation != null || fence != null) {
        if (tokenJti == null || hash == null || generation == null || fence == null) {
          throw new JournalUnavailableException();
        }
        witness =
            Optional.of(
                InitialGameplayLoginTokenWitness.newBuilder()
                    .setTokenJti(tokenJti.toString())
                    .setTokenSha256(hash)
                    .setTokenGeneration(generation)
                    .setIssuanceFence(fence)
                    .build());
      }
      byte[] bytes = row.get("retirement_request_bytes", byte[].class);
      Optional<RetireInitialGameplayLoginRequest> request = Optional.empty();
      if (bytes != null) {
        if (bytes.length == 0 || bytes.length > 512) {
          throw new JournalUnavailableException();
        }
        var parsed = RetireInitialGameplayLoginRequest.parseFrom(bytes);
        InitialGameplayLoginDisposalResult.requireOriginalRequest(parsed);
        if (!Arrays.equals(bytes, parsed.toByteArray())) {
          throw new JournalUnavailableException();
        }
        request = Optional.of(parsed);
      }
      UUID operation = row.get("disposal_operation_id", UUID.class);
      String disposition = row.get("disposal_disposition", String.class);
      String proof = row.get("disposal_proof_kind", String.class);
      Optional<InitialGameplayLoginDisposalResult> receipt = Optional.empty();
      if (operation != null || disposition != null || proof != null) {
        if (operation == null || disposition == null || proof == null) {
          throw new JournalUnavailableException();
        }
        receipt =
            Optional.of(
                new InitialGameplayLoginDisposalResult(
                    coordinates.requestId(),
                    coordinates.callerContextId(),
                    operation,
                    Disposition.valueOf(disposition),
                    ProofKind.valueOf(proof)));
      }
      return new InitialGameplayLoginJournalSnapshot(
          coordinates, required(row, "abandoned", Boolean.class), witness, request, receipt);
    } catch (InvalidProtocolBufferException | IllegalArgumentException malformed) {
      // Parser/driver messages and causes are not retained as lifecycle evidence or diagnostics.
      throw new JournalUnavailableException();
    }
  }

  private void requireMutation() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new JournalUnavailableException();
    }
    dsl.connectionResult(
        connection -> {
          requireConnection(connection, true);
          return null;
        });
  }

  private static void requireConnection(Connection connection, boolean mutation)
      throws SQLException {
    if (connection.getAutoCommit() == mutation
        || mutation
            && connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
      throw new JournalUnavailableException();
    }
    Record settings =
        DSL.using(connection, SQLDialect.POSTGRES)
            .fetchOne(
                "SELECT pg_is_in_recovery() AS recovery, current_setting('synchronous_commit') AS durability");
    if (settings == null
        || !Boolean.FALSE.equals(settings.get("recovery", Boolean.class))
        || !List.of("on", "remote_write", "remote_apply")
            .contains(settings.get("durability", String.class))) {
      throw new JournalUnavailableException();
    }
  }

  private static void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new JournalUnavailableException();
    }
  }

  private static void requireExact(
      InitialGameplayLoginAttemptCoordinates coordinates,
      InitialGameplayLoginJournalSnapshot snapshot) {
    if (!coordinates.equals(snapshot.coordinates())) {
      throw new JournalConflictException();
    }
  }

  private static <T> T required(Record row, String column, Class<T> type) {
    T value = row.get(column, type);
    if (value == null) {
      throw new JournalUnavailableException();
    }
    return value;
  }

  public static final class JournalConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public JournalConflictException() {
      super("Original LOGIN journal identity or retained evidence conflicts");
    }
  }

  public static final class JournalUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public JournalUnavailableException() {
      super("Original LOGIN journal evidence is unavailable");
    }
  }
}
