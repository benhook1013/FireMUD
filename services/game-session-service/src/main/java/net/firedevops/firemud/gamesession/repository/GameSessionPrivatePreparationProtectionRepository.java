package net.firedevops.firemud.gamesession.repository;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unregistered durable source-currentness protection only. Holds remain pending: there is no
 * release path until a separate authenticated World terminal protocol is implemented. Neither
 * acquisition nor expiry supplies actor authority, permission to materialize, or terminal proof.
 */
public final class GameSessionPrivatePreparationProtectionRepository {
  private static final String TABLE = "game_session_private_preparation_protection";
  private static final long CONTENTION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
  private final DSLContext dsl;
  private final GameSessionPrivateWorldPreparationSourceReader source;
  private final TransactionTemplate owner;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The transaction-bound DSLContext is an injected database dependency, not owned mutable value state")
  public GameSessionPrivatePreparationProtectionRepository(
      DSLContext dsl, PlatformTransactionManager transactions) {
    this.dsl = Objects.requireNonNull(dsl);
    source =
        new GameSessionPrivateWorldPreparationSourceReader(
            new GameSessionCurrentPrivatePlaytestRepository(dsl),
            new GameSessionPrivateFreshTargetRepository(dsl),
            new GameSessionPrivateFreshRuntimeAllocationRepository(dsl));
    owner = new TransactionTemplate(Objects.requireNonNull(transactions));
    owner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    owner.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    owner.setReadOnly(false);
    owner.setTimeout(5);
  }

  /** Exact request replay is historical, including after expiry; it authorizes no new effect. */
  public Hold acquire(UUID requestId, Snapshot expected) {
    independent();
    uuid(requestId);
    Objects.requireNonNull(expected);
    byte[] expectedBytes = PrivateWorldPreparationReadGrpcCodec.stableBytes(expected);
    Hold committed = acquireTransaction(requestId, expected, expectedBytes);
    // The first transaction has committed, including its deferred current-source/expiry guard.
    Hold readback =
        Objects.requireNonNull(
            owner.execute(status -> requireExact(byRequest(requestId), expectedBytes)));
    if (!committed.equals(readback))
      throw new ProtectionUnavailableException(
          "Committed private source protection readback changed");
    return readback;
  }

  private Hold acquireTransaction(UUID requestId, Snapshot expected, byte[] expectedBytes) {
    // A writer with an older SERIALIZABLE snapshot must conflict with this source read plus
    // protection insert. Retrying only an INSERT after 40001 would preserve the stale snapshot.
    long contentionDeadline = System.nanoTime() + CONTENTION_WAIT_NANOS;
    for (int attempt = 0; attempt < 3; ) {
      requireLiveCaller();
      try {
        return Objects.requireNonNull(
            owner.execute(
                status -> {
                  // A blocking lock would establish a SERIALIZABLE snapshot before waiting,
                  // consuming the source budget and potentially hiding the winner's committed row.
                  // Roll back a busy attempt and wait without retaining a transaction/connection.
                  Record locked =
                      dsl.fetchOne(
                          "SELECT pg_try_advisory_xact_lock(hashtextextended(?,0)) AS acquired",
                          "private-source-protection/" + requestId);
                  if (locked == null || !Boolean.TRUE.equals(locked.get("acquired", Boolean.class)))
                    throw new RequestContendedException();
                  Record previous = byRequest(requestId);
                  if (previous != null) return requireExact(previous, expectedBytes);
                  var readRequest =
                      new PrivateWorldPreparationReadGrpcCodec.Request(
                          UUID.randomUUID(), expected.scope(), expected.preparation().requestId());
                  Snapshot current = source.read(readRequest);
                  if (!Arrays.equals(
                      expectedBytes, PrivateWorldPreparationReadGrpcCodec.stableBytes(current)))
                    throw new ProtectionUnavailableException(
                        "Private source changed before protection");
                  var runtime = current.runtimeAllocation();
                  var selection = current.selection();
                  var target = current.target();
                  requireLiveCaller();
                  Record inserted =
                      dsl.fetchOne(
                          "INSERT INTO "
                              + TABLE
                              + " (request_id,hold_id,source_read_request_id,target_namespace,"
                              + "canonical_tenant_id,world_slug,realm_slug,realm_id,preparation_request_id,playtest_lifecycle_id,"
                              + "runtime_allocation_request_id,game_instance_id,catalog_revision,catalog_request_id,"
                              + "selection_head_revision,selection_request_id,source_bytes,source_digest,source_evidence)"
                              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING *",
                          requestId,
                          UUID.randomUUID(),
                          readRequest.readRequestId(),
                          current.scope().targetNamespace(),
                          current.scope().tenantId(),
                          current.scope().worldSlug(),
                          current.scope().realmSlug(),
                          target.realmId(),
                          current.preparation().requestId(),
                          current.preparation().lifecycleId(),
                          runtime.requestId(),
                          runtime.gameInstanceId(),
                          target.catalogRevision(),
                          target.creationRequestId(),
                          selection.headRevision(),
                          selection.requestId(),
                          expectedBytes,
                          PrivateWorldPreparationReadEvidence.sha256(expectedBytes),
                          PrivateWorldPreparationReadGrpcCodec.toResponse(readRequest, current)
                              .getCanonicalEvidence()
                              .toByteArray());
                  if (inserted == null)
                    throw new ProtectionUnavailableException(
                        "Private runtime already has another source protection");
                  return requireExact(inserted, expectedBytes);
                }));
      } catch (RequestContendedException busy) {
        // TransactionTemplate has already rolled back and released its connection.
        requireLiveCaller();
        long remaining = contentionDeadline - System.nanoTime();
        var grpcDeadline = Context.current().getDeadline();
        if (grpcDeadline != null)
          remaining = Math.min(remaining, grpcDeadline.timeRemaining(TimeUnit.NANOSECONDS));
        if (remaining <= 0)
          throw new ProtectionContendedException("Private source protection request remained busy");
        try {
          TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(25), remaining));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new ProtectionContendedException("Private source protection wait was interrupted");
        }
      } catch (RuntimeException failure) {
        if (++attempt == 3 || !serializationFailure(failure)) throw failure;
      }
    }
    throw new IllegalStateException("Unreachable protection retry");
  }

  private static void requireLiveCaller() {
    if (Context.current().isCancelled())
      throw new ProtectionContendedException("Private source protection caller was cancelled");
  }

  private static final class RequestContendedException extends RuntimeException {}

  private static boolean serializationFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState()))
        return true;
    return false;
  }

  /**
   * Requires an existing pending hold and the exact still-unexpired source under local owner locks.
   * Returning it does not grant actor authority or extend expiry beyond this observation.
   */
  public Hold readCurrent(UUID requestId, UUID holdId, long fence) {
    independent();
    uuid(requestId);
    uuid(holdId);
    if (fence <= 0) throw new IllegalArgumentException("Positive source hold fence required");
    return Objects.requireNonNull(
        owner.execute(
            status -> {
              Hold held = decode(byRequest(requestId));
              if (!held.holdId().equals(holdId) || held.fence() != fence)
                throw new ProtectionUnavailableException("Private protection identity differs");
              Snapshot current =
                  source.read(
                      new PrivateWorldPreparationReadGrpcCodec.Request(
                          UUID.randomUUID(),
                          held.source().scope(),
                          held.source().preparation().requestId()));
              if (!Arrays.equals(
                  PrivateWorldPreparationReadGrpcCodec.stableBytes(current),
                  PrivateWorldPreparationReadGrpcCodec.stableBytes(held.source())))
                throw new ProtectionUnavailableException("Protected private source changed");
              return held;
            }));
  }

  private Record byRequest(UUID requestId) {
    return dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id=?", requestId);
  }

  private static Hold requireExact(Record row, byte[] expected) {
    Hold held = decode(row);
    if (!Arrays.equals(expected, PrivateWorldPreparationReadGrpcCodec.stableBytes(held.source())))
      throw new ProtectionUnavailableException("Source protection request identity was reused");
    return held;
  }

  private static Hold decode(Record row) {
    if (row == null)
      throw new ProtectionUnavailableException("Private source protection is absent");
    Scope scope =
        new Scope(
            row.get("target_namespace", String.class),
            row.get("canonical_tenant_id", UUID.class),
            row.get("world_slug", String.class),
            row.get("realm_slug", String.class));
    var request =
        new PrivateWorldPreparationReadGrpcCodec.Request(
            row.get("source_read_request_id", UUID.class),
            scope,
            row.get("preparation_request_id", UUID.class));
    var sourceEvidence =
        PrivateWorldPreparationReadGrpcCodec.fromResponse(
            request,
            ReadPrivateWorldPreparationResponse.newBuilder()
                .setCanonicalEvidence(ByteString.copyFrom(row.get("source_evidence", byte[].class)))
                .build());
    var snapshot = sourceEvidence.snapshot();
    byte[] sourceBytes = PrivateWorldPreparationReadGrpcCodec.stableBytes(snapshot);
    if (!Arrays.equals(sourceBytes, row.get("source_bytes", byte[].class))
        || !PrivateWorldPreparationReadEvidence.sha256(sourceBytes)
            .equals(row.get("source_digest", String.class))
        || !snapshot.target().realmId().equals(row.get("realm_id", UUID.class))
        || !snapshot
            .preparation()
            .lifecycleId()
            .equals(row.get("playtest_lifecycle_id", UUID.class))
        || !snapshot
            .runtimeAllocation()
            .requestId()
            .equals(row.get("runtime_allocation_request_id", UUID.class))
        || !snapshot
            .runtimeAllocation()
            .gameInstanceId()
            .equals(row.get("game_instance_id", UUID.class))
        || snapshot.target().catalogRevision() != row.get("catalog_revision", Long.class)
        || !snapshot.target().creationRequestId().equals(row.get("catalog_request_id", UUID.class))
        || snapshot.selection().headRevision() != row.get("selection_head_revision", Long.class)
        || !snapshot.selection().requestId().equals(row.get("selection_request_id", UUID.class)))
      throw new ProtectionUnavailableException("Retained private protection evidence differs");
    return new Hold(
        row.get("request_id", UUID.class),
        row.get("hold_id", UUID.class),
        row.get("fence", Long.class),
        sourceEvidence);
  }

  public record Hold(
      UUID requestId,
      UUID holdId,
      long fence,
      PrivateWorldPreparationReadEvidence.Evidence sourceEvidence) {
    public Hold {
      uuid(requestId);
      uuid(holdId);
      if (fence <= 0) throw new IllegalArgumentException("Positive source hold fence required");
      Objects.requireNonNull(sourceEvidence);
    }

    public Snapshot source() {
      return sourceEvidence.snapshot();
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  private static void uuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Non-nil identity required");
  }

  private static void independent() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException(
          "Private source protection requires independent owner transactions");
  }

  public static final class ProtectionContendedException extends IllegalStateException {
    public ProtectionContendedException(String message) {
      super(message);
    }
  }

  public static final class ProtectionUnavailableException extends IllegalStateException {
    public ProtectionUnavailableException(String message) {
      super(message);
    }
  }
}
