package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-local immutable activation ledger and exact World lifecycle CAS. */
public final class WorldCanonicalInstanceActivationRepository {
  private final DSLContext dsl;
  private final TransactionTemplate readTransaction;
  private final TransactionTemplate writeTransaction;
  private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository;

  public WorldCanonicalInstanceActivationRepository(
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.lifecycleRepository = Objects.requireNonNull(lifecycleRepository, "lifecycleRepository");
    PlatformTransactionManager manager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    readTransaction = new TransactionTemplate(manager);
    readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    readTransaction.setReadOnly(true);
    writeTransaction = new TransactionTemplate(manager);
    writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Independently reads an immutable result; exact retries intentionally do not require current
   * authority.
   */
  public Optional<WorldCanonicalInstanceActivation.Result> readResult(
      WorldCanonicalInstanceActivation.Request request) {
    Objects.requireNonNull(request, "request");
    requireNoActiveTransaction("World canonical activation result read");
    return Optional.ofNullable(
        readTransaction.execute(
            status -> {
              requireReadOnlyRepeatableReadTransaction();
              return readResultInCurrentTransaction(request).orElse(null);
            }));
  }

  /**
   * Commits one exact PREPARING-to-ACTIVE CAS or stores a terminal stale-precondition result. The
   * caller has already verified remote/current authorities outside this transaction.
   */
  public WorldCanonicalInstanceActivation.Result activate(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceActivationService.HeldActivationAuthority held) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(held, "held");
    requireNoActiveTransaction("World canonical activation");
    return writeTransaction.execute(
        status -> {
          requireWritableTransaction();
          held.requireHeld();
          WorldCanonicalInstanceLifecycleEvidence current =
              lifecycleRepository
                  .readForActivationInOwnerTransaction(request.preparing().request())
                  .orElseThrow(
                      () ->
                          new ActivationConflictException(
                              "Canonical World activation no longer has its exact materialized owner association"));
          Optional<WorldCanonicalInstanceActivation.Result> existing =
              readResultInCurrentTransaction(request);
          if (existing.isPresent()) return existing.orElseThrow();

          boolean exactPrecondition =
              "PREPARING".equals(current.lifecycleStatus())
                  && current.lifecycleEpoch() == request.expectedLifecycleEpoch()
                  && current.rowVersion() == request.expectedRowVersion()
                  && Arrays.equals(
                      request.canonicalRequestBytes(),
                      new WorldCanonicalInstanceActivation.Request(
                              request.activationRequestId(), current)
                          .canonicalRequestBytes());
          if (!exactPrecondition) {
            WorldCanonicalInstanceActivation.Result aborted =
                new WorldCanonicalInstanceActivation.Result(
                    request,
                    WorldCanonicalInstanceActivation.Outcome.ABORTED,
                    "PRECONDITION_FAILED",
                    current);
            insertResult(request, aborted);
            held.requireHeld();
            return aborted;
          }

          WorldCanonicalInstanceLifecycleEvidence predicted = nextActiveEvidence(request, current);
          WorldCanonicalInstanceActivation.Result committed =
              new WorldCanonicalInstanceActivation.Result(
                  request, WorldCanonicalInstanceActivation.Outcome.COMMITTED, null, predicted);
          insertResult(request, committed);
          held.requireHeld();
          int updated =
              dsl.execute(
                  "UPDATE world_instance SET status='ACTIVE', lifecycle_epoch=lifecycle_epoch+1, "
                      + "row_version=row_version+1, updated_at=CURRENT_TIMESTAMP "
                      + "WHERE id=(SELECT world_instance_id FROM world_canonical_instance_association "
                      + "WHERE canonical_game_instance_id=?) AND canonical_game_instance_id=? "
                      + "AND status='PREPARING' AND lifecycle_epoch=? AND row_version=?",
                  request.canonicalGameInstanceId(),
                  request.canonicalGameInstanceId(),
                  request.expectedLifecycleEpoch(),
                  request.expectedRowVersion());
          if (updated != 1) {
            throw new ActivationConflictException(
                "Canonical World lifecycle changed before its exact activation compare-and-set");
          }
          WorldCanonicalInstanceLifecycleEvidence actual =
              lifecycleRepository
                  .readForActivationInOwnerTransaction(request.preparing().request())
                  .orElseThrow(
                      () ->
                          new InvalidActivationEvidenceException(
                              "Canonical World activation lost its owner readback"));
          if (!Arrays.equals(predicted.canonicalBytes(), actual.canonicalBytes())) {
            throw new InvalidActivationEvidenceException(
                "Canonical World activation result differs from its exact committed owner readback");
          }
          held.requireHeld();
          return committed;
        });
  }

  private Optional<WorldCanonicalInstanceActivation.Result> readResultInCurrentTransaction(
      WorldCanonicalInstanceActivation.Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT request_digest, request_bytes, result_bytes, result_digest "
                + "FROM world_canonical_instance_activation_operation WHERE activation_request_id=?",
            request.activationRequestId());
    if (row == null) return Optional.empty();
    byte[] requestBytes = required(row, "request_bytes", byte[].class);
    String digest = required(row, "request_digest", String.class);
    byte[] resultBytes = required(row, "result_bytes", byte[].class);
    if (!digest.equals(request.requestDigest())
        || !Arrays.equals(requestBytes, request.canonicalRequestBytes())) {
      throw new ActivationConflictException(
          "Canonical World activation request ID was reused with changed immutable bindings");
    }
    if (!required(row, "result_digest", String.class).equals(digest(resultBytes))) {
      throw new InvalidActivationEvidenceException(
          "Stored canonical World activation result digest is inconsistent");
    }
    try {
      WorldCanonicalInstanceActivation.Result result =
          WorldCanonicalInstanceActivation.Result.fromStored(resultBytes);
      if (!result.request().activationRequestId().equals(request.activationRequestId())
          || !Arrays.equals(result.request().canonicalRequestBytes(), requestBytes)) {
        throw new InvalidActivationEvidenceException(
            "Stored canonical World activation result differs from its exact operation identity");
      }
      return Optional.of(result);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidActivationEvidenceException(
          "Stored canonical World activation result is invalid", invalid);
    }
  }

  private void insertResult(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceActivation.Result result) {
    byte[] resultBytes = result.canonicalBytes();
    dsl.execute(
        "INSERT INTO world_canonical_instance_activation_operation "
            + "(activation_request_id,request_digest,request_bytes,preparing_evidence_bytes,"
            + "canonical_game_instance_id,world_instance_id,expected_lifecycle_epoch,expected_row_version,"
            + "outcome,terminal_code,result_lifecycle_epoch,result_row_version,result_bytes,result_digest) "
            + "VALUES (?,?,?,?,?,(SELECT world_instance_id FROM world_canonical_instance_association "
            + "WHERE canonical_game_instance_id=?),?,?,?,?,?,?,?,?)",
        request.activationRequestId(),
        request.requestDigest(),
        request.canonicalRequestBytes(),
        request.preparingEvidenceBytes(),
        request.canonicalGameInstanceId(),
        request.canonicalGameInstanceId(),
        request.expectedLifecycleEpoch(),
        request.expectedRowVersion(),
        result.outcome().name(),
        result.terminalCode(),
        result.lifecycleEvidence().lifecycleEpoch(),
        result.lifecycleEvidence().rowVersion(),
        resultBytes,
        digest(resultBytes));
  }

  private static WorldCanonicalInstanceLifecycleEvidence nextActiveEvidence(
      WorldCanonicalInstanceActivation.Request request,
      WorldCanonicalInstanceLifecycleEvidence current) {
    return new WorldCanonicalInstanceLifecycleEvidence(
        request.preparing().request(),
        current.launchBinding(),
        current.startLocation(),
        current.runtimeRoomInstanceId(),
        "ACTIVE",
        Math.addExact(request.expectedLifecycleEpoch(), 1L),
        Math.addExact(request.expectedRowVersion(), 1L),
        current.captureId(),
        current.graphSha256(),
        current.preparationInputDigest());
  }

  private void requireReadOnlyRepeatableReadTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World activation result read requires a read-only REPEATABLE READ transaction");
    }
    Record state = transactionState();
    if (!"repeatable read".equals(required(state, "isolation", String.class))
        || !"on".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "World activation result read requires a read-only REPEATABLE READ transaction");
    }
  }

  private void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "World activation requires a writable READ COMMITTED transaction");
    }
    Record state = transactionState();
    String actualIsolation = required(state, "isolation", String.class);
    if (!"read committed".equals(actualIsolation)
        || !"off".equals(required(state, "read_only", String.class))) {
      throw new IllegalStateException(
          "World activation requires a writable READ COMMITTED transaction");
    }
  }

  private Record transactionState() {
    return Objects.requireNonNull(
        dsl.fetchOne(
            "SELECT current_setting('transaction_isolation') AS isolation, "
                + "current_setting('transaction_read_only') AS read_only"),
        "World transaction state query returned no row");
  }

  private static void requireNoActiveTransaction(String label) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(label + " must not join an ambient transaction");
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    return Objects.requireNonNull(row.get(field, type), "Persisted " + field + " is null");
  }

  public static final class ActivationConflictException extends IllegalStateException {
    public ActivationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidActivationEvidenceException extends IllegalStateException {
    public InvalidActivationEvidenceException(String message) {
      super(message);
    }

    public InvalidActivationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
